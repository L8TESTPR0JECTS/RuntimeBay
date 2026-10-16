package dev.runtimebay;

import com.google.gson.*;
import dev.runtimebay.DockerModels.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

final class DockerService implements AutoCloseable {
    record Result(int exitCode, String stdout, String stderr) {}
    interface Commands { Result run(List<String> arguments, Duration timeout, Consumer<String> progress) throws IOException, InterruptedException; }
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(12);
    private final Commands commands;
    private final ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();
    private final LinkedHashMap<String, Pull> pulls = new LinkedHashMap<>();
    private final ReentrantLock[] locks = new ReentrantLock[32];
    private final ReentrantReadWriteLock[] contextLocks = new ReentrantReadWriteLock[32];
    private volatile boolean closing;
    private static final class Pull {
        final String id = UUID.randomUUID().toString();
        final Environment environment;
        final String image;
        final String createdAt = Instant.now().toString();
        final StringBuilder output = new StringBuilder();
        String state = "queued", error;
        boolean cancelled;
        Future<?> task;
        Pull(Environment environment, String image) { this.environment = environment; this.image = image; }
        synchronized void append(String text) { output.append(text); if (output.length() > 65536) output.delete(0, output.length() - 65536); }
        synchronized Operation view() { return new Operation(id, environment.name(), environment.endpoint(), image, state, output.toString(), error, createdAt); }
    }
    DockerService(Commands commands) { this.commands = commands; Arrays.setAll(locks, i -> new ReentrantLock()); Arrays.setAll(contextLocks, i -> new ReentrantReadWriteLock()); }
    static DockerService discover() {
        Path path = DockerCommandRunner.discover(); return new DockerService(path == null ? null : new DockerCommandRunner(path));
    }
    Discovery environments() {
        if (commands == null) return new Discovery(false, List.of(), "Install Docker CLI and a local engine such as Docker Desktop or Colima to connect.");
        try {
            List<JsonObject> rows = lines(execute(List.of("context", "ls", "--format", "{{json .}}"), READ_TIMEOUT, null).stdout());
            var names = rows.stream().map(o -> text(o, "Name")).filter(DockerService::validContext).toList();
            if (names.isEmpty()) return new Discovery(true, List.of(), "No Docker environments found");
            List<String> arguments = new ArrayList<>(List.of("context", "inspect")); arguments.addAll(names);
            JsonArray inspected = JsonParser.parseString(execute(arguments, READ_TIMEOUT, null).stdout()).getAsJsonArray();
            var result = new ArrayList<Environment>();
            for (JsonElement value : inspected) {
                JsonObject object = value.getAsJsonObject(); String name = text(object, "Name");
                boolean current = rows.stream().anyMatch(r -> text(r, "Name").equals(name) && bool(r, "Current"));
                result.add(environment(object, current));
            }
            return new Discovery(true, result, null);
        } catch (ApiException | JsonParseException | IllegalStateException e) { return new Discovery(true, List.of(), e.getMessage()); }
    }
    Environment addSshEnvironment(String name, String endpoint) {
        if (!validContext(name)) throw new ApiException(400, "Use letters, numbers, dots, dashes or underscores for the environment name");
        validateSsh(endpoint);
        execute(List.of("context", "create", name, "--description", "Runtime Bay SSH environment", "--docker", "host=" + endpoint), READ_TIMEOUT, null);
        return resolve(name, endpoint);
    }
    Environment updateEnvironment(String name, String expectedEndpoint, String endpoint, String description) {
        if (!validContext(name)) throw new ApiException(400, "Invalid environment name");
        Lock lock = contextLock(name).writeLock();
        if (!lock.tryLock()) throw new ApiException(409, "This host is busy. Wait for its current operation to finish, then save again.");
        try { return updateContext(name, expectedEndpoint, endpoint, description); }
        finally { lock.unlock(); }
    }
    void removeEnvironment(String name, String expectedEndpoint) {
        if (!validContext(name)) throw new ApiException(400, "Invalid environment name");
        if (name.equals("default")) throw new ApiException(400, "Docker manages the default context; it cannot be removed");
        if (expectedEndpoint == null || expectedEndpoint.isBlank()) throw new ApiException(400, "Refresh hosts before removing this environment");
        Lock lock = contextLock(name).writeLock();
        if (!lock.tryLock()) throw new ApiException(409, "This host is busy. Wait for its current operation to finish, then remove it again.");
        try {
            JsonArray inspected = JsonParser.parseString(execute(List.of("context", "inspect", name), READ_TIMEOUT, null).stdout()).getAsJsonArray();
            if (inspected.isEmpty()) throw new ApiException(404, "Docker environment not found");
            String actualEndpoint = environment(inspected.get(0).getAsJsonObject(), false).endpoint();
            if (!expectedEndpoint.equals(actualEndpoint)) throw new ApiException(409, "This host's address changed. Refresh hosts before removing it.");
            List<JsonObject> contexts = lines(execute(List.of("context", "ls", "--format", "{{json .}}"), READ_TIMEOUT, null).stdout());
            if (contexts.stream().anyMatch(c -> name.equals(text(c, "Name")) && bool(c, "Current")))
                throw new ApiException(409, "This is Docker's active context. Choose another active context in Docker before removing it.");
            synchronized (pulls) {
                if (pulls.values().stream().anyMatch(p -> p.environment.name().equals(name) && List.of("queued", "running", "cancelling").contains(p.view().state())))
                    throw new ApiException(409, "Finish or cancel this host's image pull before removing it");
            }
            // No engine command and no --force: Docker also protects its active context.
            execute(List.of("context", "rm", name), READ_TIMEOUT, null);
        } finally { lock.unlock(); }
    }
    private Environment updateContext(String name, String expectedEndpoint, String endpoint, String description) {
        if (name.equals("default")) throw new ApiException(400, "Docker manages the default context. Edit a named context instead.");
        if (expectedEndpoint == null || expectedEndpoint.isBlank()) throw new ApiException(400, "Refresh hosts before editing this environment");
        if (description == null || description.length() > 512 || description.chars().anyMatch(Character::isISOControl)) throw new ApiException(400, "Use a description of up to 512 characters on one line");
        JsonArray inspected = JsonParser.parseString(execute(List.of("context", "inspect", name), READ_TIMEOUT, null).stdout()).getAsJsonArray();
        if (inspected.isEmpty()) throw new ApiException(404, "Docker environment not found");
        Environment current = environment(inspected.get(0).getAsJsonObject(), false);
        if (!expectedEndpoint.equals(current.endpoint())) throw new ApiException(409, "This host changed while you were editing it. Refresh hosts and try again.");
        if (endpoint == null || endpoint.isBlank()) throw new ApiException(400, "Enter a host address");
        boolean addressChanged = !endpoint.equals(current.endpoint());
        if (addressChanged) {
            if (current.transport().equals("ssh")) validateSsh(endpoint);
            else if (current.transport().equals("local")) {
                try {
                    URI uri = URI.create(endpoint);
                    if (!"unix".equals(uri.getScheme()) || uri.getRawAuthority() != null || uri.getQuery() != null || uri.getFragment() != null || uri.getPath() == null || !uri.getPath().startsWith("/") || endpoint.contains(",") || endpoint.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
                } catch (IllegalArgumentException e) { throw new ApiException(400, "Use an absolute Unix socket address such as unix:///var/run/docker.sock"); }
            } else throw new ApiException(400, "Update verified TLS or unsupported connection addresses through Docker, then refresh hosts here.");
            synchronized (pulls) {
                if (pulls.values().stream().anyMatch(p -> p.environment.name().equals(name) && List.of("queued", "running", "cancelling").contains(p.view().state())))
                    throw new ApiException(409, "Finish or cancel this host's image pull before changing its address");
            }
        }
        var arguments = new ArrayList<>(List.of("context", "update", name, "--description", description));
        if (addressChanged) arguments.addAll(List.of("--docker", "host=" + endpoint));
        execute(arguments, READ_TIMEOUT, null);
        JsonArray updated = JsonParser.parseString(execute(List.of("context", "inspect", name), READ_TIMEOUT, null).stdout()).getAsJsonArray();
        return environment(updated.get(0).getAsJsonObject(), false);
    }
    Environment resolve(String name, String expectedEndpoint) {
        if (!validContext(name)) throw new ApiException(400, "Invalid environment name");
        if (expectedEndpoint == null || expectedEndpoint.isBlank()) throw new ApiException(400, "Select a Docker environment first");
        JsonArray values = JsonParser.parseString(execute(List.of("context", "inspect", name), READ_TIMEOUT, null).stdout()).getAsJsonArray();
        if (values.isEmpty()) throw new ApiException(404, "Docker environment not found");
        Environment result = environment(values.get(0).getAsJsonObject(), false);
        if (!expectedEndpoint.equals(result.endpoint())) throw new ApiException(409, "This environment's address changed. Refresh environments and select it again.");
        if (!result.supported()) throw new ApiException(400, result.reason());
        return result;
    }
    private static Environment environment(JsonObject object, boolean current) {
        String name = text(object, "Name"), endpoint = "", reason = null, transport = "unsupported";
        JsonObject metadata = child(object, "Metadata"), docker = child(child(object, "Endpoints"), "docker");
        endpoint = text(docker, "Host");
        if (endpoint.startsWith("unix://")) transport = "local";
        else if (endpoint.startsWith("ssh://")) {
            try { validateSsh(endpoint); transport = "ssh"; } catch (ApiException e) { reason = e.getMessage(); }
        } else if (endpoint.startsWith("tcp://") || endpoint.startsWith("https://")) {
            JsonArray material = child(object, "TLSMaterial").has("docker") ? child(object, "TLSMaterial").getAsJsonArray("docker") : new JsonArray();
            Set<String> files = new HashSet<>(); material.forEach(v -> files.add(v.getAsString()));
            if (!bool(docker, "SkipTLSVerify") && files.containsAll(List.of("ca.pem", "cert.pem", "key.pem"))) transport = "tls";
            else reason = "Configure a verified mutual-TLS Docker context or use SSH. Unauthenticated TCP connections are not supported.";
        } else reason = "This Docker transport is not supported. Use a local Unix socket, SSH or verified mutual TLS.";
        return new Environment(name, text(metadata, "Description"), endpoint, transport, reason == null && !transport.equals("unsupported"), reason, current);
    }
    Overview overview(String name, String endpoint) {
        Environment environment = resolve(name, endpoint);
        JsonObject info = JsonParser.parseString(on(environment, List.of("info", "--format", "{{json .}}"), READ_TIMEOUT, null).stdout()).getAsJsonObject();
        Engine engine = new Engine(text(info, "Name"), text(info, "ServerVersion"), text(info, "OperatingSystem"), text(info, "Architecture"), number(info, "NCPU").intValue(), number(info, "MemTotal").longValue(), number(info, "Containers").intValue(), number(info, "ContainersRunning").intValue(), number(info, "Images").intValue());
        var containers = lines(on(environment, List.of("container", "ls", "--all", "--no-trunc", "--format", "{{json .}}"), READ_TIMEOUT, null).stdout()).stream().map(o -> new Container(text(o, "ID"), text(o, "Names"), text(o, "Image"), text(o, "State"), text(o, "Status"), text(o, "Ports"), text(o, "CreatedAt"), label(text(o, "Labels"), "com.docker.compose.project"), label(text(o, "Labels"), "dev.runtimebay.project"))).toList();
        return new Overview(environment, engine, containers);
    }
    Inventory inventory(String name, String endpoint) {
        Environment env = resolve(name, endpoint);
        var images = lines(on(env, List.of("image", "ls", "--no-trunc", "--format", "{{json .}}"), READ_TIMEOUT, null).stdout()).stream().map(o -> new Image(text(o, "ID"), text(o, "Repository"), text(o, "Tag"), text(o, "Size"), text(o, "CreatedSince"))).toList();
        var volumes = lines(on(env, List.of("volume", "ls", "--format", "{{json .}}"), READ_TIMEOUT, null).stdout()).stream().map(o -> new Volume(text(o, "Name"), text(o, "Driver"), text(o, "Scope"))).toList();
        var networks = lines(on(env, List.of("network", "ls", "--no-trunc", "--format", "{{json .}}"), READ_TIMEOUT, null).stdout()).stream().map(o -> new Network(text(o, "ID"), text(o, "Name"), text(o, "Driver"), text(o, "Scope"))).toList();
        return new Inventory(images, volumes, networks);
    }
    Details details(String name, String endpoint, String id) {
        containerId(id); Environment env = resolve(name, endpoint);
        JsonObject object = inspect(env, id), state = child(object, "State"), config = child(object, "Config"), host = child(object, "HostConfig");
        Result logs = rawOn(env, List.of("container", "logs", "--tail", "300", "--timestamps", "--", id), READ_TIMEOUT, null);
        String output = logs.exitCode() == 0 ? logs.stdout() + logs.stderr() : "Logs unavailable: " + error(logs);
        Metrics metrics = null; String metricsError = null;
        if (bool(state, "Running") && !bool(state, "Paused")) {
            try {
                List<JsonObject> values = lines(on(env, List.of("container", "stats", "--no-stream", "--no-trunc", "--format", "{{json .}}", "--", id), READ_TIMEOUT, null).stdout());
                if (!values.isEmpty()) { JsonObject m = values.getFirst(); metrics = new Metrics(text(m, "CPUPerc"), text(m, "MemUsage"), text(m, "MemPerc"), text(m, "NetIO"), text(m, "BlockIO"), text(m, "PIDs")); }
            } catch (ApiException e) { metricsError = e.getMessage(); }
        }
        var mounts = new ArrayList<String>();
        if (object.has("Mounts")) for (JsonElement m : object.getAsJsonArray("Mounts")) {
            JsonObject mount = m.getAsJsonObject(); mounts.add((text(mount, "Name").isBlank() ? text(mount, "Type") : text(mount, "Name")) + " → " + text(mount, "Destination") + (bool(mount, "RW") ? "" : " (read only)"));
        }
        String containerName = text(object, "Name").replaceFirst("^/", "");
        return new Details(id, containerName, text(config, "Image"), text(state, "Status"), text(state, "StartedAt"), text(state, "FinishedAt"), number(state, "ExitCode").intValue(), text(child(host, "RestartPolicy"), "Name"), mounts, new ArrayList<>(child(child(object, "NetworkSettings"), "Networks").keySet()), tail(output, 65536), metrics, metricsError);
    }
    void action(String name, String endpoint, String id, String action) {
        containerId(id);
        ReentrantLock lock = locks[Math.floorMod((name + id).hashCode(), locks.length)]; lock.lock();
        try {
            Environment env = resolve(name, endpoint);
            List<String> args;
            switch (action) {
                case "start", "pause", "unpause" -> args = List.of("container", action, "--", id);
                case "stop", "restart" -> args = List.of("container", action, "--time", "10", "--", id);
                case "remove" -> {
                    if (bool(child(inspect(env, id), "State"), "Running")) throw new ApiException(409, "Stop the container before removing it");
                    args = List.of("container", "rm", "--", id);
                }
                default -> throw new ApiException(400, "Unsupported container action");
            }
            on(env, args, Duration.ofSeconds(30), null);
        } finally { lock.unlock(); }
    }
    String create(String name, String endpoint, Create definition) {
        var args = createArguments(definition);
        Environment env = resolve(name, endpoint);
        Path variables = null;
        try {
            if (definition.environment() != null && !definition.environment().isEmpty()) {
                variables = Files.createTempFile("runtimebay-container-", ".env", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                StringBuilder contents = new StringBuilder();
                definition.environment().forEach((key, value) -> contents.append(key).append('=').append(value).append('\n'));
                Files.writeString(variables, contents);
                args.addAll(args.indexOf("--"), List.of("--env-file", variables.toString()));
            }
            String id = on(env, args, Duration.ofSeconds(30), null).stdout().trim();
            containerId(id); return id;
        } catch (IOException e) { throw new ApiException(500, "Could not prepare container environment values"); }
        finally { if (variables != null) try { Files.deleteIfExists(variables); } catch (IOException e) { System.err.println("Could not remove temporary Docker environment file"); } }
    }
    static List<String> createArguments(Create d) {
        if (d == null || d.name() == null || !d.name().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) throw new ApiException(400, "Use letters, numbers, dots, dashes or underscores for the container name");
        imageReference(d.image());
        String restart = d.restartPolicy() == null ? "no" : d.restartPolicy();
        if (!List.of("no", "unless-stopped", "on-failure", "always").contains(restart)) throw new ApiException(400, "Invalid restart policy");
        var args = new ArrayList<>(List.of("container", "create", "--pull=never", "--name", d.name(), "--restart", restart, "--label", "dev.runtimebay.managed=true"));
        if (d.projectId() != null && !d.projectId().isBlank()) {
            if (!d.projectId().matches("[A-Za-z0-9-]{1,80}")) throw new ApiException(400, "Invalid workspace project");
            args.addAll(List.of("--label", "dev.runtimebay.project=" + d.projectId()));
        }
        if (d.ports() != null) {
            if (d.ports().size() > 20) throw new ApiException(400, "Too many published ports");
            Set<Integer> used = new HashSet<>();
            for (PublishedPort port : d.ports()) {
                if (port == null || port.host() < 1 || port.host() > 65535 || port.container() < 1 || port.container() > 65535 || !used.add(port.host())) throw new ApiException(400, "Use distinct TCP host ports between 1 and 65535");
                args.addAll(List.of("--publish", "127.0.0.1:" + port.host() + ":" + port.container() + "/tcp"));
            }
        }
        if (d.mounts() != null) {
            if (d.mounts().size() > 10) throw new ApiException(400, "Too many volumes");
            for (Mount mount : d.mounts()) {
                if (mount == null || mount.volume() == null || !mount.volume().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}") || mount.path() == null || !mount.path().matches("/[A-Za-z0-9_./-]+")) throw new ApiException(400, "Use a named volume and an absolute container path");
                args.addAll(List.of("--mount", "type=volume,source=" + mount.volume() + ",target=" + mount.path() + (mount.readOnly() ? ",readonly" : "")));
            }
        }
        if (d.environment() != null) {
            if (d.environment().size() > 100) throw new ApiException(400, "Too many environment values");
            d.environment().forEach((key, value) -> {
                if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || value == null || value.length() > 8192 || value.chars().anyMatch(c -> c == '\n' || c == '\r' || c == 0)) throw new ApiException(400, "Use single-line environment values with valid variable names");
            });
        }
        args.add("--"); args.add(d.image());
        if (d.arguments() != null) {
            if (d.arguments().size() > 100 || d.arguments().stream().anyMatch(v -> v == null || v.length() > 4096 || v.indexOf('\0') >= 0)) throw new ApiException(400, "Invalid command arguments");
            args.addAll(d.arguments());
        }
        return args;
    }
    Operation pull(String name, String endpoint, String image) {
        imageReference(image); Environment env = resolve(name, endpoint);
        synchronized (pulls) {
            if (closing) throw new ApiException(409, "Runtime Bay is shutting down");
            for (Pull existing : pulls.values()) {
                Operation view = existing.view();
                if (existing.environment.name().equals(name) && existing.image.equals(image) && List.of("queued", "running", "cancelling").contains(view.state())) return view;
            }
            if (pulls.values().stream().filter(p -> List.of("queued", "running", "cancelling").contains(p.view().state())).count() >= 4) throw new ApiException(429, "Wait for a current image pull to finish");
            if (pulls.size() >= 100) pulls.entrySet().removeIf(e -> !List.of("queued", "running", "cancelling").contains(e.getValue().view().state()));
            Pull pull = new Pull(env, image); pulls.put(pull.id, pull);
            pull.task = jobs.submit(() -> {
                synchronized (pull) { if (pull.cancelled) { pull.state = "cancelled"; return; } pull.state = "running"; }
                try {
                    Environment current = resolve(name, endpoint);
                    on(current, List.of("image", "pull", "--", image), Duration.ofMinutes(10), pull::append);
                    synchronized (pull) { if (!pull.cancelled) pull.state = "succeeded"; }
                } catch (Exception e) {
                    synchronized (pull) { if (!pull.cancelled) { pull.state = "failed"; pull.error = e.getMessage(); } }
                } finally { synchronized (pull) { if (pull.cancelled) pull.state = "cancelled"; } }
            });
            return pull.view();
        }
    }
    Operation operation(String id) { synchronized (pulls) { return findPull(id).view(); } }
    List<Operation> operations() {
        synchronized (pulls) { return pulls.values().stream().map(Pull::view).toList().reversed().stream().limit(10).toList(); }
    }
    Operation cancel(String id) {
        synchronized (pulls) {
            Pull pull = findPull(id);
            synchronized (pull) {
                if (List.of("queued", "running").contains(pull.state)) {
                    pull.cancelled = true; pull.state = pull.state.equals("queued") ? "cancelled" : "cancelling";
                    pull.task.cancel(true);
                }
                return pull.view();
            }
        }
    }
    private Pull findPull(String id) { Pull p = pulls.get(id); if (p == null) throw new ApiException(404, "Image operation not found"); return p; }
    private JsonObject inspect(Environment env, String id) { return JsonParser.parseString(on(env, List.of("container", "inspect", "--format", "{{json .}}", "--", id), READ_TIMEOUT, null).stdout()).getAsJsonObject(); }
    private Result on(Environment env, List<String> args, Duration timeout, Consumer<String> progress) {
        Result result = rawOn(env, args, timeout, progress); if (result.exitCode() != 0) throw new ApiException(502, error(result)); return result;
    }
    private Result rawOn(Environment env, List<String> args, Duration timeout, Consumer<String> progress) {
        Lock lock = contextLock(env.name()).readLock(); lock.lock();
        try {
            // An edit between inspection and execution must not retarget a queued command.
            resolve(env.name(), env.endpoint());
            var arguments = new ArrayList<>(List.of("--context", env.name())); arguments.addAll(args); return raw(arguments, timeout, progress);
        } finally { lock.unlock(); }
    }
    private ReentrantReadWriteLock contextLock(String name) { return contextLocks[Math.floorMod(name.hashCode(), contextLocks.length)]; }
    private Result execute(List<String> args, Duration timeout, Consumer<String> progress) {
        Result result = raw(args, timeout, progress); if (result.exitCode() != 0) throw new ApiException(502, error(result)); return result;
    }
    private Result raw(List<String> args, Duration timeout, Consumer<String> progress) {
        if (commands == null) throw new ApiException(503, "Docker CLI is not installed");
        try { return commands.run(List.copyOf(args), timeout, progress); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiException(503, "Docker operation was cancelled"); }
        catch (IOException e) { throw new ApiException(502, "Could not run Docker: " + e.getMessage()); }
    }
    private static String error(Result result) { String message = result.stderr().isBlank() ? result.stdout() : result.stderr(); return tail(message.strip(), 3000); }
    static void containerId(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw new ApiException(400, "Select a container by its complete ID"); }
    static void imageReference(String value) { if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/@-]{0,511}")) throw new ApiException(400, "Enter an image reference such as docker.io/library/nginx:alpine"); }
    static boolean validContext(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"); }
    static void validateSsh(String value) {
        try {
            URI uri = URI.create(value);
            if (!"ssh".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().startsWith("-") || uri.getQuery() != null || uri.getFragment() != null || (uri.getUserInfo() != null && !uri.getUserInfo().matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) || uri.getPort() == 0 || uri.getPort() > 65535 || !(uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().matches("/[A-Za-z0-9_./-]*"))) throw new IllegalArgumentException();
        } catch (Exception e) { throw new ApiException(400, "Use an SSH address such as ssh://user@hostname:22, without passwords or extra URL options"); }
    }
    private static List<JsonObject> lines(String output) { return output.lines().filter(v -> !v.isBlank()).map(v -> JsonParser.parseString(v).getAsJsonObject()).toList(); }
    private static String label(String labels, String key) { return Arrays.stream(labels.split(",")).filter(v -> v.startsWith(key + "=")).map(v -> v.substring(key.length() + 1)).findFirst().orElse(""); }
    private static String text(JsonObject object, String key) { JsonElement value = object.get(key); return value == null || value.isJsonNull() ? "" : value.getAsString(); }
    private static Number number(JsonObject object, String key) { return !object.has(key) || object.get(key).isJsonNull() ? 0 : object.get(key).getAsNumber(); }
    private static boolean bool(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean(); }
    private static JsonObject child(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject(); }
    private static String tail(String value, int limit) { return value.length() <= limit ? value : value.substring(value.length() - limit); }
    @Override public void close() {
        synchronized (pulls) { closing = true; for (String id : List.copyOf(pulls.keySet())) cancel(id); }
        jobs.shutdownNow();
        if (commands instanceof AutoCloseable closeable) try { closeable.close(); } catch (Exception e) { System.err.println("Docker cleanup failed: " + e.getMessage()); }
    }
}

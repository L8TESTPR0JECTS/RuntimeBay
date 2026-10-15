package dev.runtimebay;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Workflow-scoped CloudBeaver sessions behind Runtime Bay's authenticated loopback gateway. */
final class DatabaseWorkspace implements AutoCloseable {
    static final String VERSION = "26.2.2";
    record View(String state, String message, String framePath, String version) {}
    static final class Running implements AutoCloseable {
        private final URI origin;
        private final AutoCloseable stop;
        private final java.util.function.BooleanSupplier alive;
        private final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
        Running(URI origin, AutoCloseable stop) { this(origin, stop, () -> true); }
        Running(URI origin, AutoCloseable stop, java.util.function.BooleanSupplier alive) { this.origin = origin; this.stop = stop; this.alive = alive; }
        boolean isAlive() { return !stopped.get() && alive.getAsBoolean(); }
        URI origin() { return origin; }
        public void close() throws Exception { if (stopped.compareAndSet(false, true)) stop.close(); }
    }
    interface Launcher extends AutoCloseable {
        Running launch(Models.Project project, Path directory, String prefix, String theme, Consumer<String> progress) throws Exception;
        default void close() {}
    }
    private final ProjectStore projects;
    private final Launcher launcher;
    private final String origin;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();
    private final Gson gson = new Gson();
    private volatile boolean closing;
    private static final class Session {
        final String id, prefix, gate = secret();
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).followRedirects(HttpClient.Redirect.NEVER).build();
        volatile String state = "closed", message = "Open the table browser and SQL editor for this workflow.";
        volatile Running running;
        volatile Thread thread;
        volatile CountDownLatch finished = new CountDownLatch(0);
        long generation;
        Session(String id) { this.id = id; prefix = "/database/" + id + "/"; }
        View view() { return new View(state, message, state.equals("ready") ? prefix : null, VERSION); }
    }
    DatabaseWorkspace(ProjectStore projects, Launcher launcher, String origin) { this.projects = projects; this.launcher = launcher; this.origin = origin; }
    private Session session(String id) {
        projects.project(id);
        if (!id.matches("[a-zA-Z0-9-]{1,80}")) throw new ApiException(400, "Invalid workflow ID");
        return sessions.computeIfAbsent(id, Session::new);
    }
    View status(String id) {
        Session session = session(id);
        synchronized (session) {
            if (session.state.equals("ready") && (session.running == null || !session.running.isAlive())) {
                session.state = "failed"; session.message = "The database companion stopped. Open it again to reconnect.";
            }
            return session.view();
        }
    }
    View start(String id, String theme) {
        if (!List.of("light", "dark").contains(theme)) throw new ApiException(400, "Choose light or dark database appearance");
        Session session = session(id);
        synchronized (session) {
            if (closing) throw new ApiException(503, "Database workspaces are shutting down");
            if (List.of("ready", "starting", "stopping").contains(session.state)) return session.view();
            session.state = "starting"; session.message = "Preparing the database workspace…";
            long generation = ++session.generation;
            CountDownLatch finished = new CountDownLatch(1); session.finished = finished;
            jobs.submit(() -> launch(session, theme, generation, finished));
            return session.view();
        }
    }
    private void launch(Session session, String theme, long generation, CountDownLatch finished) {
        Running running = null;
        try {
            synchronized (session) { session.thread = Thread.currentThread(); if (session.generation != generation) throw new InterruptedException(); }
            Path directory = projects.directory().resolve("databases").resolve(session.id);
            prepare(directory, projects.project(session.id).name(), session.prefix, theme);
            running = launcher.launch(projects.project(session.id), directory, session.prefix, theme, value -> session.message = value);
            synchronized (session) { if (session.generation != generation || closing) throw new InterruptedException(); session.running = running; }
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (!Thread.currentThread().isInterrupted() && !closing) {
                try {
                    HttpResponse<Void> response = session.client.send(HttpRequest.newBuilder(running.origin().resolve(session.prefix)).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() == 200) break;
                } catch (IOException ignored) { /* The companion is still starting. */ }
                if (System.nanoTime() >= deadline) throw new IOException("The database workspace did not become ready. Check Docker and retry.");
                Thread.sleep(250);
            }
            if (Thread.currentThread().isInterrupted() || closing) throw new InterruptedException();
            authenticate(session, directory);
            synchronized (session) {
                if (session.generation != generation || !session.state.equals("starting") || closing) throw new InterruptedException();
                session.state = "ready"; session.message = "CloudBeaver Community · table browser and SQL editor";
            }
        } catch (Exception error) {
            if (running != null) try { running.close(); } catch (Exception ignored) {}
            synchronized (session) {
                if (session.generation == generation) session.running = null;
                if (session.generation == generation && session.state.equals("starting")) {
                    session.state = "failed";
                    session.message = error instanceof ApiException ? error.getMessage() : "Could not open the database workspace. Check that Docker is running and retry.";
                }
            }
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            synchronized (session) {
                if (session.thread == Thread.currentThread()) session.thread = null;
                if (session.state.equals("stopping")) { session.state = "closed"; session.message = "Database workspace closed. Saved connections and scripts are kept."; }
            }
            finished.countDown();
        }
    }
    static void prepare(Path directory, String name, String prefix, String theme) throws IOException {
        Files.createDirectories(directory.resolve("workspace"));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(directory.resolve("workspace"), PosixFilePermissions.fromString("rwx------"));
        Path credentials = directory.resolve(".cloudbeaver.auto.conf");
        if (!Files.exists(credentials)) {
            Properties properties = new Properties(); properties.setProperty("CB_SERVER_NAME", name + " · Runtime Bay");
            properties.setProperty("CB_ADMIN_NAME", "runtimebay"); properties.setProperty("CB_ADMIN_PASSWORD", secret() + "Aa1");
            try (Writer writer = Files.newBufferedWriter(credentials, StandardCharsets.ISO_8859_1, StandardOpenOption.CREATE_NEW)) { properties.store(writer, "Private Runtime Bay companion account"); }
            Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rw-------"));
        }
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("serverPort", 8978); server.put("rootURI", prefix); server.put("serviceURI", "/api/"); server.put("contentRoot", "web"); server.put("driversLocation", "drivers");
        server.put("database", Map.of("driver", "h2_embedded_v2", "url", "jdbc:h2:${workspace}/.data/cb.h2v2.dat", "initialDataConfiguration", "conf/initial-data.conf"));
        server.put("productSettings", Map.of("core.theming.theme", theme, "plugin.sql-editor.autoSave", true));
        Map<String, Object> app = Map.of("anonymousAccessEnabled", false, "supportsCustomConnections", true, "enabledAuthProviders", List.of("local"), "forwardProxy", false,
            "resourceQuotas", Map.of("sqlResultSetRowsLimit", 10000, "sqlMaxRunningQueries", 10));
        Path config = directory.resolve("cloudbeaver.conf"); Files.writeString(config, new Gson().toJson(Map.of("server", server, "app", app)));
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
        // Upstream's persisted runtime config overrides the generated base config on later launches.
        Path runtime = directory.resolve("workspace/.data/.cloudbeaver.runtime.conf");
        if (Files.exists(runtime)) {
            JsonObject persisted = JsonParser.parseString(Files.readString(runtime)).getAsJsonObject();
            JsonObject savedServer = persisted.has("server") ? persisted.getAsJsonObject("server") : new JsonObject();
            JsonObject settings = savedServer.has("productSettings") ? savedServer.getAsJsonObject("productSettings") : new JsonObject();
            settings.addProperty("core.theming.theme", theme); savedServer.add("productSettings", settings); persisted.add("server", savedServer);
            Path temporary = Files.createTempFile(runtime.getParent(), "runtimebay-theme-", ".tmp");
            try {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
                Files.writeString(temporary, new Gson().toJson(persisted));
                Files.move(temporary, runtime, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        }
    }
    private void authenticate(Session session, Path directory) throws Exception {
        Properties properties = new Properties(); try (Reader reader = Files.newBufferedReader(directory.resolve(".cloudbeaver.auto.conf"), StandardCharsets.ISO_8859_1)) { properties.load(reader); }
        gql(session, "mutation { openSession { valid } }", Map.of());
        String password = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("MD5").digest(properties.getProperty("CB_ADMIN_PASSWORD").getBytes(StandardCharsets.UTF_8)));
        JsonObject result = gql(session, "query ($credentials:Object) { authLogin(provider:\"local\",credentials:$credentials) { authStatus } }", Map.of("credentials", Map.of("user", "runtimebay", "password", password)));
        if (!"SUCCESS".equals(result.getAsJsonObject("authLogin").get("authStatus").getAsString())) throw new IOException("Companion login failed");
    }
    private JsonObject gql(Session session, String query, Map<String, ?> variables) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(session.running.origin().resolve(session.prefix + "api/gql")).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(Map.of("query", query, "variables", variables)))).build();
        var response = session.client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject value = JsonParser.parseString(response.body()).getAsJsonObject();
        if (response.statusCode() != 200 || value.has("errors")) throw new IOException("Companion API login failed");
        return value.getAsJsonObject("data");
    }
    void authorizeFrame(HttpExchange exchange, String id) {
        Session session = session(id);
        exchange.getResponseHeaders().add("Set-Cookie", "runtimebay-db-" + id + "=" + session.gate + "; Path=" + session.prefix + "; HttpOnly; SameSite=Strict");
    }
    void proxy(HttpExchange exchange) throws Exception {
        String path = exchange.getRequestURI().getPath(); String[] parts = path.split("/", 4);
        if (parts.length < 4 || !parts[1].equals("database")) throw new ApiException(404, "Database workspace not found");
        Session session = sessions.get(parts[2]);
        if (session == null) throw new ApiException(404, "Database workspace not found");
        String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
        if (requestOrigin != null && !origin.equals(requestOrigin)) throw new ApiException(403, "Invalid origin");
        String cookies = exchange.getRequestHeaders().getFirst("Cookie");
        String credential = cookies == null ? "" : Arrays.stream(cookies.split(";")).map(String::trim).filter(value -> value.startsWith("runtimebay-db-" + session.id + "=")).findFirst().orElse("");
        if (!MessageDigest.isEqual(("runtimebay-db-" + session.id + "=" + session.gate).getBytes(StandardCharsets.UTF_8), credential.getBytes(StandardCharsets.UTF_8))) throw new ApiException(401, "Open this database workspace from Runtime Bay");
        if (!session.state.equals("ready") || session.running == null) throw new ApiException(503, "The database workspace is not running");
        if ("websocket".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Upgrade"))) throw new ApiException(426, "Use CloudBeaver's HTTP event transport");
        String raw = exchange.getRequestURI().getRawPath();
        if (!raw.startsWith(session.prefix) || path.contains("..") || path.indexOf('\\') >= 0 || raw.toLowerCase(Locale.ROOT).matches(".*%(2f|5c).*")) throw new ApiException(400, "Invalid database path");
        String query = exchange.getRequestURI().getRawQuery();
        URI upstream = URI.create(session.running.origin() + raw + (query == null ? "" : "?" + query));
        String method = exchange.getRequestMethod();
        if (!List.of("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS").contains(method)) throw new ApiException(405, "Method not allowed");
        HttpRequest.Builder request = HttpRequest.newBuilder(upstream).timeout(Duration.ofSeconds(120));
        for (String header : List.of("Content-Type", "Accept", "Accept-Language", "Range", "If-None-Match")) {
            String value = exchange.getRequestHeaders().getFirst(header); if (value != null) request.header(header, value);
        }
        request.header("Origin", session.running.origin().toString());
        request.method(method, List.of("GET", "HEAD").contains(method) ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofInputStream(exchange::getRequestBody));
        HttpResponse<InputStream> response = session.client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        for (String header : List.of("Content-Type", "Content-Disposition", "Content-Range", "Accept-Ranges", "ETag")) response.headers().firstValue(header).ifPresent(value -> exchange.getResponseHeaders().set(header, value));
        response.headers().firstValue("Location").ifPresent(location -> {
            URI target = upstream.resolve(location);
            if (target.getRawPath().startsWith(session.prefix) && target.getHost().equals(upstream.getHost()) && target.getPort() == upstream.getPort()) exchange.getResponseHeaders().set("Location", target.getRawPath() + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery()));
        });
        // Scoped to the embedded Community application; the Runtime Bay document keeps its existing policy.
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:; object-src 'none'; base-uri 'self'; frame-ancestors 'self'");
        try (InputStream input = response.body()) {
            boolean empty = method.equals("HEAD") || List.of(204, 304).contains(response.statusCode());
            exchange.sendResponseHeaders(response.statusCode(), empty ? -1 : 0);
            if (!empty) input.transferTo(exchange.getResponseBody());
        }
    }
    View stop(String id) {
        Session session = session(id); Running running; CountDownLatch finished;
        synchronized (session) {
            session.state = "stopping"; session.message = "Closing the database workspace…"; session.generation++;
            if (session.thread != null) session.thread.interrupt();
            running = session.running; session.running = null; finished = session.finished;
        }
        if (running != null) try { running.close(); } catch (Exception ignored) {}
        try { finished.await(12, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        synchronized (session) {
            if (session.state.equals("stopping") && finished.getCount() == 0) { session.state = "closed"; session.message = "Database workspace closed. Saved connections and scripts are kept."; }
            session.client.cookieHandler().filter(CookieManager.class::isInstance).map(CookieManager.class::cast).ifPresent(manager -> manager.getCookieStore().removeAll());
            return session.view();
        }
    }
    private static String secret() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return HexFormat.of().formatHex(bytes); }
    @Override public void close() {
        closing = true; sessions.keySet().parallelStream().forEach(this::stop);
        sessions.values().forEach(session -> session.client.shutdownNow()); jobs.shutdownNow(); launcher.close();
    }
}

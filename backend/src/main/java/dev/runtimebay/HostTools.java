package dev.runtimebay;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Version probes are fixed commands, executed in the user's login shell without an agent. */
final class HostTools implements AutoCloseable {
    record Tool(String id, String name, String state, String path, String version) {}
    record RunningProcess(long pid, String name) {}
    record Inventory(String hostId, String checkedAt, String hostname, String os, String architecture, List<Tool> tools, List<RunningProcess> processes) {}
    record Result(int exitCode, String output) {}
    interface Runner { Result run(List<String> arguments) throws Exception; }
    private static final Map<String, String> TOOLS = Collections.unmodifiableMap(new LinkedHashMap<>(Map.ofEntries(
        Map.entry("python3", "Python 3"), Map.entry("pip3", "pip"), Map.entry("java", "Java"), Map.entry("javac", "Java compiler"), Map.entry("node", "Node.js"), Map.entry("npm", "npm"), Map.entry("aws", "AWS CLI"), Map.entry("git", "Git"), Map.entry("docker", "Docker CLI"), Map.entry("mvn", "Maven"), Map.entry("gradle", "Gradle"), Map.entry("pnpm", "pnpm"), Map.entry("yarn", "Yarn"), Map.entry("go", "Go"))));
    static final String SCRIPT = """
        printf 'RB_MACHINE\\t%s\\t%s\\t%s\\n' "$(hostname)" "$(uname -s)" "$(uname -m)"
        rb_probe() {
          rb_tool="$1"; shift
          rb_path="$(command -v "$rb_tool" 2>/dev/null)"
          if [ -z "$rb_path" ]; then printf 'RB_TOOL\\t%s\\tmissing\\t\\t\\n' "$rb_tool"; return; fi
          rb_version="$("$rb_tool" "$@" 2>&1)"; rb_code=$?
          rb_state=available; [ "$rb_code" -eq 0 ] || rb_state=error
          rb_path="$(printf '%s' "$rb_path" | tr '\\t\\r\\n' '   ' | head -c 512)"
          rb_version="$(printf '%s' "$rb_version" | tr '\\t\\r\\n' '   ' | head -c 768)"
          printf 'RB_TOOL\\t%s\\t%s\\t%s\\t%s\\n' "$rb_tool" "$rb_state" "$rb_path" "$rb_version"
        }
        rb_probe python3 --version
        rb_probe pip3 --version
        rb_probe java -version
        rb_probe javac -version
        rb_probe node --version
        rb_probe npm --version
        rb_probe aws --version
        rb_probe git --version
        rb_probe docker --version
        rb_probe mvn --version
        rb_probe gradle --version
        rb_probe pnpm --version
        rb_probe yarn --version
        rb_probe go version
        ps -u "$(id -u)" -o pid=,comm= | head -n 100 | while read -r rb_pid rb_name; do printf 'RB_PROCESS\\t%s\\t%s\\n' "$rb_pid" "$rb_name"; done
        """;
    private final Set<Process> processes = ConcurrentHashMap.newKeySet();
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private final Runner runner;
    private Path controls;
    private HostPasswords passwords;
    private volatile boolean closed;
    HostTools() { runner = null; }
    HostTools(Path controls) { this(); this.controls = controls; }
    HostTools(Path controls, HostPasswords passwords) { this(controls); this.passwords = passwords; }
    HostTools(Runner runner) { this.runner = runner; }
    Inventory inspect(HostRegistry.Host host) throws Exception {
        if (closed) throw new ApiException(503, "Host inspection is shutting down");
        List<String> args;
        if (host.local()) args = List.of("/bin/zsh", "-lic", SCRIPT);
        else {
            args = new ArrayList<>(HostRegistry.sshArguments(host, false, controls));
            args.add("\"${SHELL:-/bin/sh}\" -lic " + quote(SCRIPT));
        }
        Result result = runner == null ? run(args, host.id()) : runner.run(args);
        if (result.exitCode() != 0) throw new ApiException(502, host.local() ? "Could not inspect this Mac: " + tail(result.output()) : "SSH inspection failed. Use Enter credentials to supply a password; select Remember in macOS Keychain to reconnect after restarting the app. Alternatively use SSH keys/an agent or keep an authenticated host terminal open. Verify unknown host keys in Terminal first. " + tail(result.output()));
        return parse(host.id(), result.output());
    }
    static Inventory parse(String hostId, String output) {
        String hostname = "", os = "", architecture = "";
        Map<String, Tool> tools = new LinkedHashMap<>(); var processes = new ArrayList<RunningProcess>();
        for (String line : output.split("\\R")) {
            String[] values = line.split("\\t", 5);
            if (values.length == 4 && values[0].equals("RB_MACHINE")) { hostname = values[1]; os = values[2].equals("Darwin") ? "macOS" : values[2]; architecture = values[3]; }
            if (values.length == 5 && values[0].equals("RB_TOOL") && TOOLS.containsKey(values[1]) && Set.of("available", "missing", "error").contains(values[2]))
                tools.put(values[1], new Tool(values[1], TOOLS.get(values[1]), values[2], values[3], values[4]));
            if (values.length == 3 && values[0].equals("RB_PROCESS") && values[1].matches("[0-9]{1,10}") && processes.size() < 100) processes.add(new RunningProcess(Long.parseLong(values[1]), values[2]));
        }
        if (hostname.isBlank() || os.isBlank() || tools.size() != TOOLS.size()) throw new ApiException(502, "The host returned an incomplete inventory. Check its login shell and try again.");
        return new Inventory(hostId, Instant.now().toString(), hostname, os, architecture, List.copyOf(tools.values()), List.copyOf(processes));
    }
    private Result run(List<String> arguments, String hostId) throws Exception {
        if (closed) throw new ApiException(503, "Host inspection is shutting down");
        ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
        builder.environment().keySet().removeIf(k -> k.startsWith("RUNTIMEBAY_"));
        builder.environment().put("PATH", "/opt/homebrew/bin:/usr/local/bin:" + builder.environment().getOrDefault("PATH", "/usr/bin:/bin:/usr/sbin:/sbin"));
        if (passwords != null && !hostId.equals("local")) passwords.configure(builder, hostId);
        Process process = builder.start(); processes.add(process);
        Future<String> output = null;
        try {
            if (closed) throw new ApiException(503, "Host inspection is shutting down");
            process.getOutputStream().close();
            output = readers.submit(() -> {
            try (InputStream input = process.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[4096]; int count;
                while ((count = input.read(chunk)) >= 0) { if (buffer.size() + count > 131072) throw new IOException("Host inventory output exceeded its limit"); buffer.write(chunk, 0, count); }
                return buffer.toString(StandardCharsets.UTF_8);
            }
            });
            if (!process.waitFor(40, TimeUnit.SECONDS)) throw new ApiException(504, "Host inspection timed out. Check SSH access and the host's login shell.");
            return new Result(process.exitValue(), output.get(3, TimeUnit.SECONDS));
        } finally { if (output != null) output.cancel(true); terminate(process); processes.remove(process); }
    }
    private static void terminate(Process process) { process.descendants().forEach(ProcessHandle::destroy); if (process.isAlive()) process.destroyForcibly(); }
    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
    private static String tail(String value) { return value.substring(Math.max(0, value.length() - 1800)).strip(); }
    @Override public void close() { closed = true; processes.forEach(HostTools::terminate); readers.shutdownNow(); }
}

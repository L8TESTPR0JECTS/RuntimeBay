package dev.runtimebay;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

final class DockerCommandRunner implements DockerService.Commands, AutoCloseable {
    private static final int OUTPUT_LIMIT = 4 * 1024 * 1024;
    private final Path executable;
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Process> running = ConcurrentHashMap.newKeySet();
    private final Semaphore permits = new Semaphore(6);
    private volatile boolean closing;

    DockerCommandRunner(Path executable) { this.executable = executable; }
    static Path discover() {
        String override = System.getenv("RUNTIMEBAY_DOCKER");
        if (override != null && !override.isBlank()) return Files.isExecutable(Path.of(override)) ? Path.of(override).toAbsolutePath() : null;
        var candidates = new ArrayList<String>();
        for (String directory : System.getenv().getOrDefault("PATH", "").split(":")) candidates.add(directory + "/docker");
        candidates.addAll(List.of("/usr/local/bin/docker", "/opt/homebrew/bin/docker", "/Applications/Docker.app/Contents/Resources/bin/docker"));
        return candidates.stream().map(Path::of).filter(Files::isExecutable).findFirst().map(Path::toAbsolutePath).orElse(null);
    }
    @Override public DockerService.Result run(List<String> arguments, Duration timeout, Consumer<String> progress) throws IOException, InterruptedException {
        if (closing) throw new IOException("Container integration is shutting down");
        if (!permits.tryAcquire(5, TimeUnit.SECONDS)) throw new ApiException(429, "Docker is busy. Try again shortly.");
        Process process = null;
        try {
            if (closing) throw new IOException("Container integration is shutting down");
            List<String> command = new ArrayList<>(); command.add(executable.toString()); command.addAll(arguments);
            ProcessBuilder builder = new ProcessBuilder(command);
            cleanEnvironment(builder.environment());
            builder.environment().put("PATH", "/Applications/Docker.app/Contents/Resources/bin:/opt/homebrew/bin:/usr/local/bin:" + builder.environment().getOrDefault("PATH", "/usr/bin:/bin:/usr/sbin:/sbin"));
            builder.environment().put("SSH_ASKPASS_REQUIRE", "never");
            process = builder.start();
            running.add(process);
            if (closing) throw new IOException("Container integration is shutting down");
            process.getOutputStream().close();
            Process launched = process;
            Future<Capture> stdout = readers.submit(() -> capture(launched.getInputStream(), progress));
            Future<Capture> stderr = readers.submit(() -> capture(launched.getErrorStream(), progress));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new ApiException(504, "Docker did not finish in time. Check the selected host before retrying.");
            try {
                Capture out = stdout.get(3, TimeUnit.SECONDS), err = stderr.get(3, TimeUnit.SECONDS);
                if (out.truncated || err.truncated) throw new ApiException(502, "Docker output exceeded the size limit");
                return new DockerService.Result(process.exitValue(), out.text, err.text);
            } catch (ExecutionException | TimeoutException e) { throw new IOException("Could not read Docker output", e); }
        } finally {
            if (process != null) { terminate(process); running.remove(process); }
            permits.release();
        }
    }
    static void cleanEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(k -> k.startsWith("RUNTIMEBAY_"));
        for (String key : List.of("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH")) environment.remove(key);
    }
    private record Capture(String text, boolean truncated) {}
    private Capture capture(InputStream input, Consumer<String> progress) throws IOException {
        StringBuilder retained = new StringBuilder();
        boolean truncated = false;
        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096]; int count;
            while ((count = reader.read(buffer)) >= 0) {
                String chunk = new String(buffer, 0, count);
                if (progress != null) progress.accept(chunk);
                int space = OUTPUT_LIMIT - retained.length();
                if (space > 0) retained.append(chunk, 0, Math.min(space, chunk.length()));
                if (chunk.length() > space) truncated = true;
            }
        }
        return new Capture(retained.toString(), truncated);
    }
    private void terminate(Process process) {
        if (!process.isAlive()) return;
        var children = process.descendants().toList();
        children.forEach(ProcessHandle::destroy);
        process.destroy();
        try { process.waitFor(500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
    }
    @Override public void close() {
        closing = true; running.forEach(this::terminate); readers.shutdownNow();
    }
}

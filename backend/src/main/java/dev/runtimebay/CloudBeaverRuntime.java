package dev.runtimebay;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Starts only app-owned companions on a local Docker engine, with no Docker socket mounted. */
final class CloudBeaverRuntime implements DatabaseWorkspace.Launcher {
    static final String IMAGE = "dbeaver/cloudbeaver:26.2.2@sha256:7298c59289e9519d8913083c85f3ceb475aa12db0f58e87b6491445a9b298d2c";
    private final DockerService docker;
    private final Path executable;
    private final DockerCommandRunner commands;
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Process> processes = ConcurrentHashMap.newKeySet();
    private final Object imageLock = new Object();
    CloudBeaverRuntime(DockerService docker) {
        this.docker = docker; executable = DockerCommandRunner.discover(); commands = executable == null ? null : new DockerCommandRunner(executable);
    }
    public DatabaseWorkspace.Running launch(Models.Project project, Path directory, String prefix, String theme, Consumer<String> progress) throws Exception {
        if (commands == null) throw new ApiException(503, "Install Docker Desktop or another local Docker engine to open database workspaces.");
        var discovery = docker.environments();
        var locals = discovery.environments().stream().filter(environment -> environment.supported() && environment.transport().equals("local")).toList();
        var environment = locals.stream().filter(DockerModels.Environment::current).findFirst().orElse(locals.isEmpty() ? null : locals.getFirst());
        if (environment == null) throw new ApiException(503, "Connect a local Docker engine before opening the database workspace.");
        List<String> target = List.of("--host", environment.endpoint());
        run(target, List.of("info", "--format", "{{.ServerVersion}}"), Duration.ofSeconds(12), null);
        synchronized (imageLock) {
            var inspect = raw(target, List.of("image", "inspect", IMAGE), Duration.ofSeconds(12), null);
            if (inspect.exitCode() != 0) {
                progress.accept("Downloading CloudBeaver Community. This is needed only on first use…");
                run(target, List.of("image", "pull", IMAGE), Duration.ofMinutes(8), ignored -> {});
            }
        }
        progress.accept("Starting the table browser and SQL editor…");
        String name = "runtimebay-db-" + project.id() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String user = Files.getAttribute(directory, "unix:uid") + ":" + Files.getAttribute(directory, "unix:gid");
        List<String> arguments = new ArrayList<>(List.of(executable.toString())); arguments.addAll(target);
        arguments.addAll(List.of("run", "--rm", "--init", "--interactive", "--name", name, "--label", "dev.runtimebay.component=database-workspace", "--label", "dev.runtimebay.project=" + project.id(),
            "--publish", "127.0.0.1::8978", "--memory", "2g", "--cpus", "2", "--user", user, "--env", "HOME=/opt/cloudbeaver/workspace", "--env", "JAVA_OPTS=-Xms128m -Xmx1024m",
            "--volume", directory.resolve("workspace") + ":/opt/cloudbeaver/workspace", "--volume", directory.resolve("cloudbeaver.conf") + ":/opt/cloudbeaver/conf/cloudbeaver.conf:ro",
            "--volume", directory.resolve(".cloudbeaver.auto.conf") + ":/opt/cloudbeaver/conf/.cloudbeaver.auto.conf:ro", "--entrypoint", "/bin/sh", IMAGE, "-c",
            "exec 3<&0; (cat <&3 >/dev/null; kill -TERM 1) & exec ./launch-product.sh"));
        ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true); DockerCommandRunner.cleanEnvironment(builder.environment());
        Process process = builder.start(); processes.add(process);
        readers.submit(() -> { try (var input = process.getInputStream()) { input.transferTo(OutputStream.nullOutputStream()); } catch (IOException ignored) {} });
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (process.isAlive() && System.nanoTime() < deadline) {
                var port = raw(target, List.of("container", "port", name, "8978/tcp"), Duration.ofSeconds(3), null);
                String value = port.stdout().strip();
                if (port.exitCode() == 0 && value.matches("127\\.0\\.0\\.1:[0-9]{1,5}")) {
                    URI upstream = URI.create("http://" + value);
                    return new DatabaseWorkspace.Running(upstream, () -> stop(process), process::isAlive);
                }
                Thread.sleep(100);
            }
            throw new ApiException(502, "The database companion could not start. Check Docker's available memory and retry.");
        } catch (Exception error) { stop(process); throw error; }
    }
    private DockerService.Result raw(List<String> target, List<String> arguments, Duration timeout, Consumer<String> progress) throws Exception {
        var command = new ArrayList<>(target); command.addAll(arguments); return commands.run(command, timeout, progress);
    }
    private void run(List<String> target, List<String> arguments, Duration timeout, Consumer<String> progress) throws Exception {
        if (raw(target, arguments, timeout, progress).exitCode() != 0) throw new ApiException(502, "Could not prepare the database companion. Check that Docker is running and has network access.");
    }
    private void stop(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroy(); if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            }
        } catch (InterruptedException error) { process.destroy(); interrupted = true; }
        finally { processes.remove(process); if (interrupted) Thread.currentThread().interrupt(); }
    }
    @Override public void close() { processes.parallelStream().forEach(this::stop); if (commands != null) commands.close(); readers.shutdownNow(); }
}

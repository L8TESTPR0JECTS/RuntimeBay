package dev.runtimebay;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Live, app-owned PTYs. Neither commands nor terminal output are persisted. */
final class TerminalManager implements AutoCloseable {
    record View(String id, String name, String directory, Integer exitCode) {}
    record Output(String data, long next, boolean truncated, Integer exitCode) {}
    private final ProjectStore projects;
    private final Path launcher;
    private final LinkedHashMap<String, Shell> shells = new LinkedHashMap<>();
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private int number;
    private boolean closed;
    TerminalManager(ProjectStore projects, Path launcher) { this.projects = projects; this.launcher = launcher; }
    synchronized List<View> list() { return shells.values().stream().map(Shell::view).toList(); }
    synchronized View create(String projectId) throws IOException {
        if (closed) throw new ApiException(503, "Terminals are shutting down");
        Path directory = projectId == null || projectId.isBlank() ? Path.of(System.getProperty("user.home")) : Path.of(projects.project(projectId).path());
        if (!Files.isDirectory(directory)) throw new ApiException(400, "The terminal folder is no longer available");
        if (!Files.isExecutable(launcher)) throw new ApiException(503, "The terminal supervisor is missing. Rebuild Runtime Bay.");
        ProcessBuilder builder = new ProcessBuilder(launcher.toAbsolutePath().toString()).directory(directory.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("RUNTIMEBAY_"));
        builder.environment().put("TERM", "xterm-256color"); builder.environment().put("COLORTERM", "truecolor");
        // Never leave an unread stderr pipe behind; diagnostics do not contain shell output.
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Shell shell = new Shell(UUID.randomUUID().toString(), "Terminal " + (++number), directory.toString(), builder.start());
        shells.put(shell.id, shell); readers.submit(shell::read);
        return shell.view();
    }
    private synchronized Shell shell(String id) {
        Shell shell = shells.get(id); if (shell == null) throw new ApiException(404, "Terminal not found"); return shell;
    }
    Output output(String id, long after) throws InterruptedException { return shell(id).output(after); }
    void input(String id, String data) throws IOException {
        if (data == null) throw new ApiException(400, "Terminal input is required");
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 16384) throw new ApiException(400, "Terminal input exceeds 16 KiB");
        shell(id).send('I', bytes);
    }
    void resize(String id, int columns, int rows) throws IOException {
        if (columns < 2 || columns > 1000 || rows < 1 || rows > 1000) throw new ApiException(400, "Invalid terminal dimensions");
        shell(id).send('R', new byte[]{(byte)(columns >> 8), (byte)columns, (byte)(rows >> 8), (byte)rows});
    }
    void remove(String id) { Shell shell; synchronized (this) { shell = shells.remove(id); } if (shell == null) throw new ApiException(404, "Terminal not found"); shell.close(); }
    @Override public void close() {
        List<Shell> current; synchronized (this) { closed = true; current = List.copyOf(shells.values()); shells.clear(); }
        current.parallelStream().forEach(Shell::close); readers.shutdownNow();
    }
    private static final class Shell {
        private static final int CAPACITY = 1024 * 1024;
        final String id, name, directory;
        final Process process;
        final DataOutputStream input;
        final byte[] buffer = new byte[CAPACITY];
        long next;
        Integer exitCode;
        boolean closing;
        Shell(String id, String name, String directory, Process process) {
            this.id = id; this.name = name; this.directory = directory; this.process = process; input = new DataOutputStream(process.getOutputStream());
        }
        synchronized View view() { return new View(id, name, directory, exitCode); }
        void read() {
            try (InputStream output = process.getInputStream()) {
                byte[] bytes = new byte[16384]; int count;
                while ((count = output.read(bytes)) != -1) synchronized (this) {
                    for (int i = 0; i < count; i++) buffer[(int)((next + i) % CAPACITY)] = bytes[i];
                    next += count; notifyAll();
                }
            } catch (IOException ignored) {
                // A closed PTY is expected when a tab or the application closes.
            } finally {
                try { int status = process.waitFor(); synchronized (this) { exitCode = status; notifyAll(); } }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        synchronized Output output(long after) throws InterruptedException {
            if (after < 0 || after > next) throw new ApiException(400, "Invalid terminal output cursor");
            if (after == next && exitCode == null && !closing) wait(10000);
            long start = Math.max(after, next - CAPACITY);
            byte[] bytes = new byte[(int)(next - start)];
            for (int i = 0; i < bytes.length; i++) bytes[i] = buffer[(int)((start + i) % CAPACITY)];
            return new Output(Base64.getEncoder().encodeToString(bytes), next, start > after, exitCode);
        }
        void send(int type, byte[] bytes) throws IOException {
            synchronized (input) {
                synchronized (this) { if (closing || exitCode != null) throw new ApiException(409, "This shell has exited. Open a new terminal to continue."); }
                input.writeByte(type); input.writeInt(bytes.length); input.write(bytes); input.flush();
            }
        }
        void close() {
            synchronized (this) { closing = true; notifyAll(); }
            try { input.close(); } catch (IOException ignored) {}
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
            } catch (InterruptedException e) { process.destroy(); Thread.currentThread().interrupt(); }
        }
    }
}

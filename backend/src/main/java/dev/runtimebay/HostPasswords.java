package dev.runtimebay;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Session-only SSH passwords. The askpass helper receives a random capability, never the password in argv or environment. */
final class HostPasswords implements AutoCloseable {
    private record Secret(String token, char[] value) {}
    private final Map<String, Secret> secrets = new HashMap<>();
    private final Path helper, socket;
    private final ServerSocketChannel server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private final Set<SocketChannel> clients = ConcurrentHashMap.newKeySet();
    private final Semaphore permits = new Semaphore(16);
    private boolean closed;
    HostPasswords(Path directory, Path helper) throws IOException {
        this.helper = helper; socket = directory.resolve("a");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        Files.setPosixFilePermissions(socket, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        workers.submit(() -> {
            try { while (server.isOpen()) {
                SocketChannel client = server.accept();
                if (!permits.tryAcquire()) { client.close(); continue; }
                clients.add(client);
                try { workers.submit(() -> serve(client)); }
                catch (RejectedExecutionException e) { clients.remove(client); permits.release(); client.close(); }
            } } catch (IOException ignored) { /* Closing the app closes accept. */ }
        });
    }
    static void validate(String password) {
        if (password != null && (password.length() > 4096 || password.indexOf('\0') >= 0 || password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0))
            throw new ApiException(400, "Enter an SSH password without line breaks, up to 4096 characters");
    }
    synchronized void set(String id, String password) {
        validate(password);
        if (closed) throw new ApiException(503, "Hosts are shutting down");
        clear(id);
        if (password != null && !password.isEmpty()) secrets.put(id, new Secret(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""), password.toCharArray()));
    }
    synchronized void clear(String id) { Secret old = secrets.remove(id); if (old != null) Arrays.fill(old.value(), '\0'); }
    synchronized boolean has(String id) { return secrets.containsKey(id); }
    synchronized void configure(ProcessBuilder builder, String id) {
        Secret secret = secrets.get(id); if (secret == null) return;
        if (!Files.isExecutable(helper)) throw new ApiException(503, "The SSH password helper is missing. Rebuild Runtime Bay.");
        builder.environment().put("SSH_ASKPASS", helper.toAbsolutePath().toString());
        builder.environment().put("SSH_ASKPASS_REQUIRE", "force");
        builder.environment().put("DISPLAY", "runtimebay");
        builder.environment().put("RUNTIMEBAY_SSH_SOCKET", socket.toString());
        builder.environment().put("RUNTIMEBAY_SSH_TOKEN", secret.token());
        // Batch inspection may authenticate through askpass; unknown host keys remain rejected.
        int batch = builder.command().indexOf("BatchMode=yes");
        if (batch > 0 && builder.command().get(batch - 1).equals("-o")) {
            builder.command().remove(batch); builder.command().remove(batch - 1);
        }
        int target = builder.command().indexOf("--");
        builder.command().addAll(target, List.of("-o", "BatchMode=no", "-o", "NumberOfPasswordPrompts=1"));
    }
    private void serve(SocketChannel client) {
        ScheduledFuture<?> deadline = deadlines.schedule(() -> { try { client.close(); } catch (IOException ignored) {} }, 3, TimeUnit.SECONDS);
        byte[] bytes = null; char[] copy = null;
        try (client) {
            ByteBuffer token = ByteBuffer.allocate(64);
            while (token.hasRemaining()) if (client.read(token) < 0) return;
            String supplied = new String(token.array(), StandardCharsets.US_ASCII);
            synchronized (this) {
                for (Secret secret : secrets.values()) if (java.security.MessageDigest.isEqual(supplied.getBytes(StandardCharsets.US_ASCII), secret.token().getBytes(StandardCharsets.US_ASCII))) { copy = secret.value().clone(); break; }
            }
            if (copy == null) return;
            ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(copy));
            bytes = new byte[encoded.remaining()]; encoded.get(bytes);
            if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte)0);
            ByteBuffer reply = ByteBuffer.wrap(bytes); while (reply.hasRemaining()) client.write(reply);
        } catch (IOException ignored) { /* Failed authentication is reported by SSH, without credential logging. */ }
        finally { if (copy != null) Arrays.fill(copy, '\0'); if (bytes != null) Arrays.fill(bytes, (byte)0); deadline.cancel(false); clients.remove(client); permits.release(); }
    }
    @Override public synchronized void close() {
        closed = true; for (Secret secret : secrets.values()) Arrays.fill(secret.value(), '\0'); secrets.clear();
        try { server.close(); } catch (IOException ignored) {}
        clients.forEach(client -> { try { client.close(); } catch (IOException ignored) {} });
        workers.shutdownNow(); deadlines.shutdownNow();
        try { Files.deleteIfExists(socket); } catch (IOException ignored) {}
    }
}

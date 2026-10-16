package dev.runtimebay;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

final class MachineHosts implements AutoCloseable {
    record Status(String state, HostTools.Inventory inventory, String error) {}
    private final HostRegistry registry;
    private final HostTools tools;
    private final ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Status> statuses = new HashMap<>();
    private final Map<String, Future<?>> tasks = new HashMap<>();
    private final Semaphore permits = new Semaphore(4);
    private boolean closed;
    MachineHosts(HostRegistry registry, HostTools tools) { this.registry = registry; this.tools = tools; }
    HostRegistry.View view() { return registry.view(); }
    HostRegistry.Host get(String id) { return registry.get(id); }
    synchronized HostRegistry.Host save(String id, String name, String address, String user, Integer port) throws IOException {
        var host = registry.save(id, name, address, user, port); clear(host.id()); return host;
    }
    void makeDefault(String id) throws IOException { registry.makeDefault(id); }
    synchronized void remove(String id) throws IOException { registry.remove(id); clear(id); }
    private void clear(String id) { Future<?> task = tasks.remove(id); if (task != null) task.cancel(true); statuses.remove(id); }
    synchronized Status status(String id) { registry.get(id); return statuses.getOrDefault(id, new Status("idle", null, null)); }
    synchronized Status inspect(String id) {
        var host = registry.get(id);
        if (closed) throw new ApiException(503, "Hosts are shutting down");
        if (status(id).state().equals("scanning")) return status(id);
        if (!permits.tryAcquire()) throw new ApiException(429, "Wait for an active host inspection to finish");
        Status running = new Status("scanning", statuses.get(id) == null ? null : statuses.get(id).inventory(), null); statuses.put(id, running);
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                var inventory = tools.inspect(host);
                synchronized (this) { if (statuses.get(id) == running) statuses.put(id, new Status("ready", inventory, null)); }
            } catch (Exception e) {
                synchronized (this) { if (statuses.get(id) == running) statuses.put(id, new Status("error", running.inventory(), e.getMessage() == null ? "Could not inspect this host" : e.getMessage())); }
            }
            return null;
        }) { @Override protected void done() { permits.release(); } };
        tasks.put(id, task); jobs.execute(task);
        return running;
    }
    @Override public synchronized void close() { closed = true; tasks.values().forEach(t -> t.cancel(true)); tools.close(); jobs.shutdownNow(); }
}

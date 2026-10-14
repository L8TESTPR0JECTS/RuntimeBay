package dev.runtimebay;

import dev.runtimebay.Models.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class PortInspector {
    private final Path launcher;
    private final Map<Integer, Cached> cache = new HashMap<>();
    record Cached(long time, List<Owner> owners, String status) {}
    PortInspector(Path launcher) { this.launcher = launcher; }
    synchronized void invalidate(Integer port) { if (port != null) cache.remove(port); }
    synchronized Port inspect(Integer port, Long group) {
        if (port == null) return null;
        Cached cached = cache.get(port);
        if (cached == null || System.nanoTime() - cached.time() > 2_000_000_000L) {
            cached = query(port);
            cache.put(port, cached);
        }
        List<Owner> owners = cached.owners().stream()
            .map(o -> new Owner(o.pid(), o.command(), group != null && belongsTo(group, o.pid()))).toList();
        return new Port(port, cached.status(), owners);
    }
    private Cached query(int port) {
        Process process = null;
        try {
            process = new ProcessBuilder("/usr/sbin/lsof", "-nP", "-a", "-iTCP:" + port, "-sTCP:LISTEN", "-Fpc")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(800, TimeUnit.MILLISECONDS)) return unknown();
            String output = new String(process.getInputStream().readNBytes(32768), StandardCharsets.UTF_8);
            if (process.exitValue() != 0 && process.exitValue() != 1) return unknown();
            List<Owner> owners = new ArrayList<>();
            long pid = -1;
            for (String line : output.lines().toList()) {
                if (line.startsWith("p")) pid = Long.parseLong(line.substring(1));
                else if (line.startsWith("c") && pid > 0) owners.add(new Owner(pid, line.substring(1), false));
            }
            return new Cached(System.nanoTime(), owners, owners.isEmpty() ? "available" : "listening");
        } catch (IOException | InterruptedException | NumberFormatException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return unknown();
        } finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
    private Cached unknown() { return new Cached(System.nanoTime(), List.of(), "unknown"); }
    private boolean belongsTo(long group, long pid) {
        Process process = null;
        try {
            process = new ProcessBuilder(launcher.toString(), "--owns-pid", "" + group, "" + pid)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(500, TimeUnit.MILLISECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        } finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}

package dev.runtimebay;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Machine identities are independent of Docker and never contain credentials. */
final class HostRegistry {
    record Host(String id, String name, String address, String user, Integer port, boolean local) {}
    record View(List<Host> hosts, String defaultHostId) {}
    private record Saved(int version, List<Host> hosts, String defaultHostId) {}
    static final Host LOCAL = new Host("local", "This Mac", "", "", null, true);
    private final Path file;
    private final Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private List<Host> remotes = List.of();
    private String defaultId = "local";
    HostRegistry(Path directory) throws IOException {
        file = directory.resolve("hosts.json");
        if (Files.exists(file)) try {
            Saved saved = gson.fromJson(Files.readString(file), Saved.class);
            if (saved == null || saved.version() != 1 || saved.hosts() == null || saved.hosts().size() > 100) throw new IllegalArgumentException();
            Set<String> ids = new HashSet<>();
            for (Host host : saved.hosts()) {
                if (host == null || host.local() || host.id() == null || !host.id().equals(UUID.fromString(host.id()).toString()) || !ids.add(host.id())) throw new IllegalArgumentException();
                validate(host.name(), host.address(), host.user(), host.port());
            }
            remotes = List.copyOf(saved.hosts()); defaultId = saved.defaultHostId(); get(defaultId);
        } catch (RuntimeException e) { throw new IOException("Cannot read saved hosts. Existing data has been preserved.", e); }
    }
    synchronized View view() { var all = new ArrayList<Host>(); all.add(LOCAL); all.addAll(remotes); return new View(List.copyOf(all), defaultId); }
    synchronized Host get(String id) { return viewHosts().stream().filter(h -> h.id().equals(id)).findFirst().orElseThrow(() -> new ApiException(404, "Host not found")); }
    private List<Host> viewHosts() { var all = new ArrayList<Host>(); all.add(LOCAL); all.addAll(remotes); return all; }
    synchronized Host save(String id, String name, String address, String user, Integer port) throws IOException {
        validate(name, address, user, port);
        if ("local".equals(id)) throw new ApiException(400, "This Mac is managed automatically");
        if (id != null) get(id);
        else if (remotes.size() >= 100) throw new ApiException(400, "Remove a saved host before adding another");
        Host host = new Host(id == null ? UUID.randomUUID().toString() : id, name.trim(), address.trim(), user.trim(), port, false);
        var next = new ArrayList<>(remotes); next.removeIf(h -> h.id().equals(host.id())); next.add(host);
        commit(next, defaultId); return host;
    }
    synchronized void makeDefault(String id) throws IOException { get(id); commit(remotes, id); }
    synchronized void remove(String id) throws IOException {
        get(id);
        if (id.equals("local")) throw new ApiException(400, "This Mac stays available as your local host");
        if (id.equals(defaultId)) throw new ApiException(409, "Make another host default before removing this one");
        commit(remotes.stream().filter(h -> !h.id().equals(id)).toList(), defaultId);
    }
    private void commit(List<Host> hosts, String preferred) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "hosts-", ".tmp");
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(temporary, gson.toJson(new Saved(1, hosts, preferred)));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            remotes = List.copyOf(hosts); defaultId = preferred;
        } finally { Files.deleteIfExists(temporary); }
    }
    static void validate(String name, String address, String user, Integer port) {
        if (name == null || name.isBlank() || name.length() > 128 || name.chars().anyMatch(Character::isISOControl)) throw new ApiException(400, "Enter a host name of up to 128 characters");
        if (address == null || !address.trim().matches("[A-Za-z0-9:][A-Za-z0-9_.:%-]{0,254}")) throw new ApiException(400, "Enter a hostname, IP address, or SSH configuration alias, such as helfer");
        if (user == null || !user.isEmpty() && !user.trim().matches("[A-Za-z_][A-Za-z0-9_.-]{0,63}")) throw new ApiException(400, "Enter a valid SSH user or leave it blank to use your SSH configuration");
        if (port != null && (port < 1 || port > 65535)) throw new ApiException(400, "SSH port must be between 1 and 65535");
    }
    static List<String> sshArguments(Host host, boolean interactive) {
        return sshArguments(host, interactive, null);
    }
    static List<String> sshArguments(Host host, boolean interactive, Path controls) {
        return sshArguments(host, interactive, controls, null);
    }
    static List<String> sshArguments(Host host, boolean interactive, Path controls, String sshAlias) {
        if (host.local()) throw new IllegalArgumentException("Local hosts do not use SSH");
        validate(host.name(), host.address(), host.user(), host.port());
        var args = new ArrayList<>(List.of("/usr/bin/ssh", interactive ? "-tt" : "-T", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=8", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=2"));
        if (!interactive) args.addAll(List.of("-o", "BatchMode=yes"));
        if (controls != null) {
            String identity = host.id().replaceAll("[^A-Za-z0-9]", "");
            args.addAll(List.of("-o", "ControlMaster=auto", "-o", "ControlPersist=no", "-o", "ControlPath=" + controls.resolve(identity.substring(0, Math.min(8, identity.length())) + "-%C")));
        }
        if ((sshAlias == null || sshAlias.isBlank()) && host.port() != null) args.addAll(List.of("-p", host.port().toString()));
        args.add("--"); args.add(sshAlias == null || sshAlias.isBlank() ? (host.user().isEmpty() ? "" : host.user() + "@") + host.address() : sshAlias);
        return args;
    }
}

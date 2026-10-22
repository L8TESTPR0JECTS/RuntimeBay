package dev.runtimebay;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Environment-scoped references. Attaching never launches a process or acquires lifecycle ownership. */
final class WorkflowAttachments {
    record Source(String kind, String projectId, String sourceId, String scope) {}
    record Entry(String id, String workflowId, String environmentId, Source source, String name) {}
    record Snapshot(String projectId, String checkedAt, List<DatabaseWorkspace.SavedConnection> connections) {}
    record Registry(int version, List<Entry> entries, List<Snapshot> databases) {}
    record Choice(String key, Source source, String name, String detail, String ownership) {}
    record View(Entry reference, String key, String name, String state, String detail, String ownership, List<String> actions) {}
    record Workspace(String id, String name) {}
    record Catalog(List<Choice> choices, List<Workspace> databaseWorkspaces) {}
    private final ProjectStore projects;
    private final WorkflowStore workflows;
    private final Path file;
    private final Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private Registry registry = new Registry(1, List.of(), List.of());
    WorkflowAttachments(ProjectStore projects, WorkflowStore workflows) throws IOException {
        this.projects = projects; this.workflows = workflows; file = projects.directory().resolve("workflow-attachments.json");
        reload();
    }
    synchronized void reload() throws IOException {
        if (Files.exists(file)) try {
            var saved = gson.fromJson(Files.readString(file), Registry.class); validate(saved); registry = saved;
        } catch (RuntimeException error) { throw new IOException("Cannot read workflow-attachments.json. Existing data is preserved; restore a valid reference registry before retrying.", error); }
        else registry=new Registry(1,List.of(),List.of());
    }
    static String key(Source s) { return WorkflowStore.stable("attachment-source", s.kind() + "\0" + s.projectId() + "\0" + s.scope() + "\0" + s.sourceId()); }
    synchronized List<Choice> choices(HostRegistry.View hosts, List<WorkflowContainers.Link> containers) {
        var result = new ArrayList<Choice>();
        for (var project : projects.list()) {
            for (var service : project.services()) result.add(new Choice(key(new Source("service", project.id(), service.id(), "")), new Source("service", project.id(), service.id(), ""), service.name(), project.name() + " · configured service; inspect at source", "managed-at-source"));
        }
        for (var host : hosts.hosts()) {
            var source = new Source("terminal", "", host.id(), "");
            result.add(new Choice(key(source), source, host.name() + " terminal", "Saved terminal intent · opens a new session only when requested", "external"));
        }
        for (var snapshot : registry.databases()) if (projects.list().stream().anyMatch(p -> p.id().equals(snapshot.projectId()))) for (var connection : snapshot.connections()) {
            var source = new Source("database", snapshot.projectId(), connection.id(), connection.scope());
            result.add(new Choice(key(source), source, connection.name(), projects.project(snapshot.projectId()).name() + " · CloudBeaver " + connection.scope() + " · metadata checked " + snapshot.checkedAt(), "external"));
        }
        for (var container : containers) {
            var source = new Source("container", container.projectId(), container.id(), "");
            result.add(new Choice(WorkflowStore.stable("container", container.context() + "\0" + container.endpoint() + "\0" + container.containerId()), source, container.name(), container.context() + " · " + container.image() + " · " + container.containerId().substring(0, 12), "external"));
        }
        var unique = new LinkedHashMap<String, Choice>(); for (var choice : result) unique.putIfAbsent(choice.key(), choice);
        return List.copyOf(unique.values());
    }
    synchronized Entry attach(String workflowId, String environmentId, Source source, HostRegistry.View hosts, List<WorkflowContainers.Link> containers) throws IOException {
        environment(workflowId, environmentId);
        if (source == null || "container".equals(source.kind())) throw new ApiException(400, "Use the existing container linking controls");
        var choice = choices(hosts, containers).stream().filter(c -> c.source().equals(source)).findFirst().orElseThrow(() -> new ApiException(409, "The selected source is missing. Refresh the resource catalog; existing references are preserved."));
        if (source.kind().equals("service") && source.projectId().equals(workflowId)) throw new ApiException(409, "This service is already configured in this workflow");
        var existing = registry.entries().stream().filter(e -> e.workflowId().equals(workflowId) && e.environmentId().equals(environmentId) && e.source().equals(source)).findFirst().orElse(null);
        if (existing != null) return existing;
        var entry = new Entry(UUID.randomUUID().toString(), workflowId, environmentId, source, choice.name());
        var entries = new ArrayList<>(registry.entries()); entries.add(entry); commit(new Registry(1, List.copyOf(entries), registry.databases())); return entry;
    }
    synchronized List<Entry> all(String workflowId){return registry.entries().stream().filter(e->e.workflowId().equals(workflowId)).toList();}
    synchronized Entry importReference(String workflowId,String environmentId,String id,Source source,HostRegistry.View hosts,List<WorkflowContainers.Link> containers)throws IOException{
        if(id==null)return attach(workflowId,environmentId,source,hosts,containers);
        var previous=find(workflowId,id);environment(workflowId,environmentId);if(!previous.environmentId().equals(environmentId))throw new ApiException(409,"Imported association belongs to another environment");
        var choice=choices(hosts,containers).stream().filter(c->c.source().equals(source)&&c.source().kind().equals(previous.source().kind())).findFirst().orElseThrow(()->new ApiException(409,"Choose an existing replacement of the same kind"));
        if(source.kind().equals("service")&&source.projectId().equals(workflowId))throw new ApiException(409,"Service is already configured here");
        var duplicate=registry.entries().stream().filter(e->!e.id().equals(id)&&e.workflowId().equals(workflowId)&&e.environmentId().equals(environmentId)&&e.source().equals(source)).findFirst().orElse(null);
        if(duplicate!=null)return duplicate;
        var replacement=new Entry(id,workflowId,environmentId,source,choice.name());commit(new Registry(1,registry.entries().stream().map(e->e.id().equals(id)?replacement:e).toList(),registry.databases()));return replacement;
    }
    synchronized void unlinkImported(String workflowId,String id)throws IOException{var entry=find(workflowId,id);commit(new Registry(1,registry.entries().stream().filter(e->!e.id().equals(entry.id())).toList(),registry.databases()));}
    synchronized Entry rebind(String workflowId, String id, Source source, HostRegistry.View hosts, List<WorkflowContainers.Link> containers) throws IOException {
        var previous = find(workflowId, id); selected(previous); environment(workflowId, previous.environmentId());
        var choice = choices(hosts, containers).stream().filter(c -> c.source().equals(source) && c.source().kind().equals(previous.source().kind())).findFirst().orElseThrow(() -> new ApiException(409, "Choose an existing source of the same kind"));
        if (source.kind().equals("service") && source.projectId().equals(workflowId)) throw new ApiException(409, "This service is already configured in this workflow");
        if (registry.entries().stream().anyMatch(e -> !e.id().equals(id) && e.workflowId().equals(workflowId) && e.environmentId().equals(previous.environmentId()) && e.source().equals(source))) throw new ApiException(409, "This source is already attached in this environment");
        var replacement = new Entry(id, workflowId, previous.environmentId(), source, choice.name());
        commit(new Registry(1, registry.entries().stream().map(e -> e.id().equals(id) ? replacement : e).toList(), registry.databases())); return replacement;
    }
    synchronized void unlink(String workflowId, String id) throws IOException {
        var entry = find(workflowId, id); selected(entry);
        commit(new Registry(1, registry.entries().stream().filter(e -> !e.id().equals(entry.id())).toList(), registry.databases()));
    }
    private void selected(Entry entry) throws IOException {
        if (!workflows.get(entry.workflowId()).defaultEnvironmentId().equals(entry.environmentId())) throw new ApiException(409, "This reference belongs to another environment. Select that environment before changing it.");
    }
    private Entry find(String workflowId, String id) { return registry.entries().stream().filter(e -> e.workflowId().equals(workflowId) && e.id().equals(id)).findFirst().orElseThrow(() -> new ApiException(404, "Workflow attachment not found")); }
    synchronized List<View> view(String workflowId, HostRegistry.View hosts, DatabaseWorkspace databases, Set<String> unavailableHosts) throws IOException {
        var workflow = workflows.get(workflowId); var choices = choices(hosts, List.of());
        return registry.entries().stream().filter(e -> e.workflowId().equals(workflowId) && e.environmentId().equals(workflow.defaultEnvironmentId())).map(e -> {
            var choice = choices.stream().filter(c -> c.source().equals(e.source())).findFirst().orElse(null);
            if (choice == null) return new View(e, key(e.source()), e.name(), "missing", "The original source is missing. Rebind explicitly to a replacement, or unlink this reference.", e.source().kind().equals("service") ? "managed-at-source" : "external", List.of("rebind", "unlink"));
            boolean database = e.source().kind().equals("database");
            boolean hostUnavailable = e.source().kind().equals("terminal") && unavailableHosts.contains(e.source().sourceId());
            boolean available = !hostUnavailable && (!database || projects.list().stream().anyMatch(p -> p.id().equals(e.source().projectId())) && databases.status(e.source().projectId()).state().equals("ready"));
            String action = switch (e.source().kind()) { case "service" -> "inspect-service"; case "terminal" -> "open-terminal"; default -> "open-database-workspace"; };
            return new View(e, key(e.source()), choice.name(), available ? "saved" : "unavailable", available ? choice.detail() + " · Saved reference; not a live health check" : hostUnavailable ? "Host inspection failed. Open its terminal to sign in, or inspect it again in Hosts; this intent is retained." : "Database workspace is closed or unavailable. Open its source workspace and refresh connections; this reference is retained.", choice.ownership(), List.of(action, "rebind", "unlink"));
        }).toList();
    }
    synchronized View resolve(String workflowId, String id, HostRegistry.View hosts, DatabaseWorkspace databases, Set<String> unavailableHosts) throws IOException {
        return view(workflowId, hosts, databases, unavailableHosts).stream().filter(v -> v.reference().id().equals(id)).findFirst().orElseThrow(() -> new ApiException(409, "This attachment is not in the selected environment. Refresh its controls."));
    }
    synchronized void requireReferenceKey(String workflowId, String id, String expected) throws IOException {
        var entry = find(workflowId, id); selected(entry);
        if (expected == null || !key(entry.source()).equals(expected)) throw new ApiException(409, "This reference changed while you were viewing it. Refresh before opening, rebinding, or unlinking it.");
    }
    synchronized void refreshDatabase(String projectId, List<DatabaseWorkspace.SavedConnection> connections) throws IOException {
        projects.project(projectId);
        var snapshots = new ArrayList<>(registry.databases()); snapshots.removeIf(s -> s.projectId().equals(projectId));
        snapshots.add(new Snapshot(projectId, java.time.Instant.now().toString(), List.copyOf(connections)));
        commit(new Registry(1, registry.entries(), List.copyOf(snapshots)));
    }
    synchronized void protectEnvironments(String workflowId, WorkflowModels.Workflow replacement) {
        if (replacement == null || replacement.environments() == null) return;
        for (var entry : registry.entries()) if (entry.workflowId().equals(workflowId) && replacement.environments().stream().noneMatch(e -> e.id().equals(entry.environmentId())))
            throw new ApiException(409, "This environment has attached resources. Unlink its attachments before removing the environment; source resources are never deleted.");
    }
    synchronized void protectHost(String hostId) {
        if (registry.entries().stream().anyMatch(e -> e.source().kind().equals("terminal") && e.source().sourceId().equals(hostId))) throw new ApiException(409, "This host has saved workflow terminal intents. Unlink or rebind those intents before removing or changing the host.");
    }
    private void environment(String workflowId, String environmentId) throws IOException {
        if (workflows.get(workflowId).environments().stream().noneMatch(e -> e.id().equals(environmentId))) throw new ApiException(409, "Choose an existing workflow environment");
    }
    private void commit(Registry next) throws IOException {
        validate(next); Path temp = Files.createTempFile(file.getParent(), "attachments-", ".tmp");
        try { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")); Files.writeString(temp, gson.toJson(next)); Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); registry = next; }
        finally { Files.deleteIfExists(temp); }
    }
    private static void text(String value) { if (value == null || value.isBlank() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid attachment metadata"); }
    private static void validate(Registry saved) {
        if (saved == null || saved.version() != 1 || saved.entries() == null || saved.databases() == null || saved.entries().size() > 5000 || saved.databases().size() > 1000) throw new IllegalArgumentException("Invalid attachment registry");
        Set<String> ids = new HashSet<>(); Set<List<String>> pairs = new HashSet<>();
        for (var e : saved.entries()) {
            text(e.id()); text(e.workflowId()); text(e.environmentId()); text(e.name());
            var source = e.source(); if (source == null || !List.of("service", "terminal", "database").contains(source.kind()) || source.scope() == null || source.projectId() == null) throw new IllegalArgumentException("Unsupported attachment source");
            text(source.sourceId());
            if (source.kind().equals("terminal")) { if (!source.projectId().isEmpty() || !source.scope().isEmpty()) throw new IllegalArgumentException("Invalid terminal intent scope"); }
            else { text(source.projectId()); if (source.kind().equals("database")) text(source.scope()); else if (!source.scope().isEmpty()) throw new IllegalArgumentException("Invalid service scope"); }
            if (!ids.add(e.id()) || !pairs.add(List.of(e.workflowId(), e.environmentId(), key(source)))) throw new IllegalArgumentException("Duplicate attachment");
        }
        Set<String> snapshots = new HashSet<>();
        for (var snapshot : saved.databases()) {
            text(snapshot.projectId()); text(snapshot.checkedAt()); if (!snapshots.add(snapshot.projectId()) || snapshot.connections() == null || snapshot.connections().size() > 1000) throw new IllegalArgumentException("Invalid connection snapshot");
            Set<List<String>> connections = new HashSet<>();
            for (var c : snapshot.connections()) { text(c.scope()); text(c.id()); text(c.name()); if (!connections.add(List.of(c.scope(), c.id()))) throw new IllegalArgumentException("Duplicate connection"); }
        }
    }
}

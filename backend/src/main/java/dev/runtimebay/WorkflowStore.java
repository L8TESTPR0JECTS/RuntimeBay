package dev.runtimebay;

import com.google.gson.*;
import dev.runtimebay.Models.Project;
import dev.runtimebay.WorkflowModels.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Versioned graph over existing definitions. Migration never rewrites the source registries. */
final class WorkflowStore {
    private final ProjectStore projects;
    private final Path file;
    private final Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private List<Workflow> workflows = List.of();

    WorkflowStore(ProjectStore projects) throws IOException {
        this.projects = projects;
        file = projects.directory().resolve("workflows.json");
        if (Files.exists(file)) try {
            Registry registry = gson.fromJson(Files.readString(file), Registry.class);
            if (registry == null || registry.version() != 1 || registry.workflows() == null) throw new IllegalArgumentException();
            Set<String> ids = new HashSet<>();
            for (Workflow workflow : registry.workflows()) {
                validate(workflow);
                if (!ids.add(workflow.id())) throw new IllegalArgumentException("Duplicate workflow");
            }
            workflows = List.copyOf(registry.workflows());
        } catch (RuntimeException error) { throw new IOException("Cannot read workflows.json. Existing data has been preserved. Restore a valid version or rename this file before retrying migration; keep the original for recovery.", error); }
        reconcile();
    }

    synchronized List<Workflow> list() throws IOException { reconcile(); return workflows; }
    synchronized Workflow get(String id) throws IOException {
        reconcile();
        return workflows.stream().filter(w -> w.id().equals(id)).findFirst().orElseThrow(() -> new ApiException(404, "Workflow not found"));
    }
    synchronized Workflow save(String id, Workflow value) throws IOException {
        Workflow current = get(id);
        validate(value);
        if (!current.id().equals(value.id()) || !current.projectId().equals(value.projectId())) throw new ApiException(400, "Workflow and project identities cannot change");
        if (!current.name().equals(value.name()) || !new HashSet<>(current.resources()).equals(new HashSet<>(value.resources())))
            throw new ApiException(400, "Edit service definitions and container links through their existing controls; workflow resources must retain their source identities");
        Map<String, DockerConnection> connections = new HashMap<>();
        for (DockerConnection connection : current.dockerConnections()) connections.put(connection.id(), connection);
        for (var option : containerOptions()) connections.putIfAbsent(option.connection().id(), option.connection());
        for (DockerConnection connection : value.dockerConnections()) {
            DockerConnection existing = connections.get(connection.id());
            if (existing == null || !existing.context().equals(connection.context()) || !existing.endpoint().equals(connection.endpoint()))
                throw new ApiException(400, "Choose an existing linked container; Docker endpoint identities cannot be retargeted through a workflow");
        }
        var savedLinks = readLinks();
        for (Binding binding : value.bindings()) if (binding.target().linkId() != null) {
            var link = savedLinks.stream().filter(l -> l.id().equals(binding.target().linkId())).findFirst().orElse(null);
            if (link == null) {
                if (!current.bindings().contains(binding)) throw new ApiException(409, "The selected container link is missing. Refresh and resolve the binding.");
            } else if (!binding.target().id().equals(stable("docker", link.context() + "\0" + link.endpoint())))
                throw new ApiException(400, "Container identity does not match its pinned Docker connection");
        } else if (binding.target().kind().equals("docker")) {
            var link = savedLinks.stream().filter(l -> l.id().equals(binding.resourceId())).findFirst().orElseThrow();
            if (!binding.target().id().equals(stable("docker", link.context() + "\0" + link.endpoint())))
                throw new ApiException(400, "Choose a container identity as well as its Docker connection");
        }
        var next = new ArrayList<>(workflows); next.set(next.indexOf(current), value); commit(next); return get(id);
    }

    synchronized List<ContainerOption> containerOptions() throws IOException {
        return readLinks().stream().map(l -> new ContainerOption(l.id(), l.name(), l.image(),
            new DockerConnection(stable("docker", l.context() + "\0" + l.endpoint()), l.context(), l.endpoint(), null))).toList();
    }
    synchronized void requireUnusedMachine(String id) throws IOException {
        for (Workflow workflow : list()) for (Binding binding : workflow.bindings()) {
            Target target = binding.target();
            boolean used = target.kind().equals("machine") && target.id().equals(id) || target.kind().equals("docker") && workflow.dockerConnections().stream().anyMatch(c -> c.id().equals(target.id()) && id.equals(c.machineId()));
            if (used) throw inUse(workflow, binding);
        }
    }
    synchronized void requireUnusedDocker(String context) throws IOException {
        for (Workflow workflow : list()) for (Binding binding : workflow.bindings())
            if (binding.target().kind().equals("docker") && workflow.dockerConnections().stream().anyMatch(c -> c.id().equals(binding.target().id()) && c.context().equals(context))) throw inUse(workflow, binding);
    }
    synchronized void requireUnsharedLink(String id) throws IOException {
        for (Workflow workflow : list()) for (Binding binding : workflow.bindings())
            if (id.equals(binding.target().linkId()) && !id.equals(binding.resourceId())) throw inUse(workflow, binding);
    }
    private ApiException inUse(Workflow workflow, Binding binding) {
        String environment = workflow.environments().stream().filter(e -> e.id().equals(binding.environmentId())).findFirst().orElseThrow().name();
        return new ApiException(409, "Target is used by " + workflow.name() + " / " + environment + ". Resolve its resource bindings before removing or changing this target.");
    }
    synchronized List<WorkflowContainers.Link> effectiveContainers(String projectId) throws IOException {
        Workflow workflow = get(projectId);
        List<WorkflowContainers.Link> links = readLinks();
        var result = new ArrayList<WorkflowContainers.Link>();
        for (Binding binding : workflow.bindings()) if (binding.environmentId().equals(workflow.defaultEnvironmentId()) && binding.target().kind().equals("docker")) {
            String linkId = binding.target().linkId() == null ? binding.resourceId() : binding.target().linkId();
            var link = links.stream().filter(l -> l.id().equals(linkId)).findFirst().orElseThrow(() -> new ApiException(409, "A selected environment container is unresolved. Review its resource binding."));
            var connection = workflow.dockerConnections().stream().filter(c -> c.id().equals(binding.target().id())).findFirst().orElseThrow();
            if (!connection.context().equals(link.context()) || !connection.endpoint().equals(link.endpoint())) throw new ApiException(409, "Container target changed. Resolve the binding before execution.");
            result.add(new WorkflowContainers.Link(binding.resourceId(), projectId, link.context(), link.endpoint(), link.containerId(), link.name(), link.image()));
        }
        return List.copyOf(result);
    }

    synchronized void requireLocalService(String projectId, String serviceId) throws IOException {
        Workflow workflow = get(projectId);
        Binding binding = workflow.bindings().stream().filter(b -> b.environmentId().equals(workflow.defaultEnvironmentId()) && b.resourceId().equals(serviceId)).findFirst()
            .orElseThrow(() -> new ApiException(409, "Service has no resolved workflow binding; resolve its target before starting"));
        if (!binding.target().kind().equals("machine") || !binding.target().id().equals(HostRegistry.LOCAL.id()))
            throw new ApiException(409, "This service is bound to another or unresolved machine. Remote service execution is not available yet; Runtime Bay will not run it on This Mac.");
    }

    View view(Workflow workflow, HostRegistry.View hosts) throws IOException {
        var links = readLinks();
        Map<String, HostRegistry.Host> machines = new HashMap<>();
        for (var host : hosts.hosts()) machines.put(host.id(), host);
        Map<String, Resource> resources = new HashMap<>();
        for (var resource : workflow.resources()) resources.put(resource.id(), resource);
        Map<String, DockerConnection> connections = new HashMap<>();
        for (var connection : workflow.dockerConnections()) connections.put(connection.id(), connection);
        List<BindingView> views = workflow.bindings().stream().filter(b -> b.environmentId().equals(workflow.defaultEnvironmentId())).map(binding -> {
            Resource resource = resources.get(binding.resourceId()); Target target = binding.target();
            if (target.kind().equals("machine")) {
                var host = machines.get(target.id());
                return new BindingView(binding.id(), resource.id(), resource.name(), resource.type(), target.kind(), target.id(), host == null ? target.id() : host.name(),
                    host == null ? "unresolved" : host.local() ? "local" : "unsupported",
                    host == null ? "Saved machine is missing. Resolve this binding before execution." : host.local() ? "Runs on This Mac" : "Remote service execution is planned; local fallback is disabled.");
            }
            DockerConnection connection = connections.get(target.id());
            String linkId = target.linkId() == null ? resource.id() : target.linkId();
            var link = links.stream().filter(l -> l.id().equals(linkId)).findFirst().orElse(null);
            var machine = connection.machineId() == null ? null : machines.get(connection.machineId());
            return new BindingView(binding.id(), resource.id(), resource.name(), resource.type(), target.kind(), target.id(), connection.context(),
                link == null || connection.machineId() != null && machine == null ? "unresolved" : "linked",
                (link == null ? "Missing container link · " : link.name() + " · " + link.containerId().substring(0, 12) + " · ") + connection.endpoint() + (connection.machineId() != null && machine == null ? " · Saved machine reference is missing" : " · Docker connection; machine identity is separate"));
        }).toList();
        return new View(workflow, views, revision(workflow));
    }

    String revision(Workflow workflow) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(gson.toJson(workflow).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    synchronized void requireSelection(String projectId, String expected, String expectedRevision) throws IOException {
        if (expectedRevision != null && !revision(get(projectId)).equals(expectedRevision)) throw new ApiException(409, "Resource bindings changed. Refresh before using their controls.");
        if (expected != null && !get(projectId).defaultEnvironmentId().equals(expected)) throw new ApiException(409, "The selected environment changed. Refresh before using its controls.");
    }
    private void reconcile() throws IOException {
        // Read and validate every legacy source before making the only migration write.
        List<WorkflowContainers.Link> links = readLinks();
        List<Project> sources = projects.list();
        Set<String> projectIds = new HashSet<>(), serviceIds = new HashSet<>();
        for (Project source : sources) {
            if (!projectIds.add(source.id())) throw new IOException("Cannot migrate duplicate project identities. Source files were preserved; resolve duplicate IDs in projects.json before retrying.");
            for (var service : source.services()) {
                if (service == null || service.id() == null) throw new IOException("Cannot migrate an invalid service identity. Source files were preserved; restore valid services in projects.json before retrying.");
                if (!serviceIds.add(service.id())) throw new IOException("Cannot migrate duplicate service identities. Source files were preserved; resolve duplicate IDs in projects.json before retrying.");
            }
        }
        if (links.stream().anyMatch(link -> !projectIds.contains(link.projectId()))) throw new IOException("Cannot migrate a container link with a missing project. Source files were preserved; restore the project or resolve the link before retrying.");
        Map<String, Workflow> existing = new HashMap<>();
        for (Workflow workflow : workflows) existing.put(workflow.projectId(), workflow);
        var next = new ArrayList<Workflow>();
        for (Project project : sources) {
            Workflow previous = existing.get(project.id());
            List<Environment> environments = previous == null ? List.of(new Environment(stable("environment", project.id()), "Local")) : previous.environments();
            String selected = previous == null ? environments.getFirst().id() : previous.defaultEnvironmentId();
            var resources = new ArrayList<Resource>();
            var connections = new LinkedHashMap<String, DockerConnection>();
            var defaults = new HashMap<String, Target>();
            for (var service : project.services()) {
                resources.add(new Resource(service.id(), "service", service.id(), service.name(), "managed"));
                defaults.put(service.id(), new Target("machine", HostRegistry.LOCAL.id()));
            }
            for (var link : links) if (link.projectId().equals(project.id())) {
                String connectionId = stable("docker", link.context() + "\0" + link.endpoint());
                DockerConnection connection = previous == null ? null : previous.dockerConnections().stream().filter(c -> c.id().equals(connectionId)).findFirst().orElse(null);
                connections.put(connectionId, connection == null ? new DockerConnection(connectionId, link.context(), link.endpoint(), null) : connection);
                resources.add(new Resource(link.id(), "container", link.id(), link.name(), "external"));
                defaults.put(link.id(), new Target("docker", connectionId));
            }
            if (previous != null) for (var connection : previous.dockerConnections())
                if (previous.bindings().stream().anyMatch(b -> b.target().kind().equals("docker") && b.target().id().equals(connection.id()) && resources.stream().anyMatch(r -> r.id().equals(b.resourceId())))) connections.putIfAbsent(connection.id(), connection);
            var bindings = new ArrayList<Binding>();
            for (Environment environment : environments) for (Resource resource : resources) {
                Binding old = previous == null ? null : previous.bindings().stream().filter(b -> b.environmentId().equals(environment.id()) && b.resourceId().equals(resource.id())).findFirst().orElse(null);
                bindings.add(old == null ? new Binding(stable("binding", environment.id() + "\0" + resource.id()), environment.id(), resource.id(), defaults.get(resource.id())) : old);
            }
            var machines = previous == null ? List.of(new MachineReference(HostRegistry.LOCAL.id())) : previous.machines();
            // Newly added legacy services are always explicitly local, regardless of preferred terminal host.
            if (machines.stream().noneMatch(m -> m.id().equals(HostRegistry.LOCAL.id()))) { var all = new ArrayList<>(machines); all.add(new MachineReference(HostRegistry.LOCAL.id())); machines = List.copyOf(all); }
            Workflow workflow = new Workflow(project.id(), project.id(), project.name(), selected, List.copyOf(environments), machines, List.copyOf(connections.values()), List.copyOf(resources), List.copyOf(bindings));
            try { validate(workflow); } catch (RuntimeException error) { throw new IOException("Cannot migrate workflow definitions. projects.json, workflow-containers.json, and workflows.json were preserved. Resolve invalid or duplicate source identities before retrying.", error); }
            next.add(workflow);
        }
        if (!next.equals(workflows) || !Files.exists(file)) commit(next);
    }
    private List<WorkflowContainers.Link> readLinks() throws IOException {
        Path source = projects.directory().resolve("workflow-containers.json");
        if (!Files.exists(source)) return List.of();
        try {
            var registry = gson.fromJson(Files.readString(source), WorkflowContainers.Registry.class);
            if (registry == null || registry.version() != 1 || registry.links() == null) throw new IllegalArgumentException();
            Set<String> ids = new HashSet<>();
            for (var link : registry.links()) {
                if (link == null) throw new IllegalArgumentException();
                id(link.id()); id(link.projectId()); text(link.context(), 1024); text(link.endpoint(), 4096); text(link.name(), 1024); text(link.image(), 4096);
                if (!ids.add(link.id())) throw new IllegalArgumentException("Duplicate link identity");
                DockerService.containerId(link.containerId());
            }
            return registry.links();
        } catch (RuntimeException error) { throw new IOException("Cannot migrate workflow-containers.json. Existing data has been preserved; restore valid container links before retrying.", error); }
    }
    private void commit(List<Workflow> next) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "workflows-", ".tmp");
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(temporary, gson.toJson(new Registry(1, next)));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            workflows = List.copyOf(next);
        } finally { Files.deleteIfExists(temporary); }
    }
    static String stable(String namespace, String value) { return UUID.nameUUIDFromBytes((namespace + ":" + value).getBytes(StandardCharsets.UTF_8)).toString(); }
    private static void id(String value) { text(value, 128); }
    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl)) throw new ApiException(400, "Invalid workflow reference or name");
    }
    private static <T> Map<String, T> index(List<T> list, java.util.function.Function<T, String> key, int limit) {
        if (list == null || list.size() > limit) throw new ApiException(400, "Invalid workflow collection");
        Map<String, T> values = new HashMap<>();
        for (T value : list) { if (value == null) throw new ApiException(400, "Null workflow entry"); String id = key.apply(value); id(id); if (values.put(id, value) != null) throw new ApiException(400, "Duplicate workflow identity"); }
        return values;
    }
    static void validate(Workflow workflow) {
        if (workflow == null) throw new ApiException(400, "A workflow definition is required");
        id(workflow.id()); id(workflow.projectId()); text(workflow.name(), 128);
        if (!workflow.id().equals(workflow.projectId())) throw new ApiException(400, "Workflow identity must retain its project identity");
        var environments = index(workflow.environments(), Environment::id, 20);
        if (!environments.containsKey(workflow.defaultEnvironmentId())) throw new ApiException(400, "Choose a defined default environment");
        Set<String> names = new HashSet<>();
        for (var environment : environments.values()) { text(environment.name(), 128); if (!names.add(environment.name().strip().toLowerCase(Locale.ROOT))) throw new ApiException(400, "Environment names must be unique"); }
        var machines = index(workflow.machines(), MachineReference::id, 200);
        var connections = index(workflow.dockerConnections(), DockerConnection::id, 1000);
        for (var connection : connections.values()) { text(connection.context(), 1024); text(connection.endpoint(), 4096); if (connection.machineId() != null && !machines.containsKey(connection.machineId())) throw new ApiException(400, "Docker machine reference is not declared"); }
        var resources = index(workflow.resources(), Resource::id, 1000);
        for (var resource : resources.values()) {
            id(resource.sourceId()); text(resource.name(), 1024);
            if (!resource.id().equals(resource.sourceId()) || !("service".equals(resource.type()) && "managed".equals(resource.ownership()) || "container".equals(resource.type()) && "external".equals(resource.ownership()))) throw new ApiException(400, "Unsupported workflow resource or ownership");
        }
        var bindings = index(workflow.bindings(), Binding::id, 20000);
        Set<List<String>> pairs = new HashSet<>();
        for (var binding : bindings.values()) {
            Resource resource = resources.get(binding.resourceId()); Target target = binding.target();
            if (!environments.containsKey(binding.environmentId()) || resource == null || target == null || !pairs.add(List.of(binding.environmentId(), binding.resourceId()))) throw new ApiException(400, "Invalid or duplicate resource binding");
            if (target.linkId() != null) { id(target.linkId()); if (!target.kind().equals("docker")) throw new ApiException(400, "Only Docker targets can reference a container link"); }
            if (resource.type().equals("service") && (!"machine".equals(target.kind()) || !machines.containsKey(target.id())) || resource.type().equals("container") && (!"docker".equals(target.kind()) || !connections.containsKey(target.id()))) throw new ApiException(400, "Resource target kind or reference is invalid");
        }
        if (pairs.size() != environments.size() * resources.size()) throw new ApiException(400, "Each resource needs an explicit binding in each environment");
    }
}

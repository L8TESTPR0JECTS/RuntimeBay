package dev.runtimebay;

import com.google.gson.Gson;
import dev.runtimebay.WorkflowModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentBindingsTest {
    @TempDir Path temporary;
    ProjectStore projects;
    WorkflowStore workflows;
    String project, service;
    WorkflowContainers containers;
    DockerServiceTest.FakeDocker fake;
    DockerService docker;
    @BeforeEach void setup() throws Exception {
        projects = new ProjectStore(temporary.resolve("data"));
        project = projects.addProject("Gobble", temporary.toString()).id();
        service = projects.saveService(project, null, "API", "printf original", ".", 4321).id();
        fake = new DockerServiceTest.FakeDocker(); docker = new DockerService(fake);
        containers = new WorkflowContainers(projects, docker); workflows = new WorkflowStore(projects);
    }
    @AfterEach void close() { docker.close(); }
    Workflow duplicate(Workflow w, String id, String name) {
        var environments = new ArrayList<>(w.environments()); environments.add(new Environment(id, name));
        var bindings = new ArrayList<>(w.bindings());
        for (var b : w.bindings()) if (b.environmentId().equals(w.defaultEnvironmentId())) bindings.add(new Binding(UUID.randomUUID().toString(), id, b.resourceId(), b.target()));
        return new Workflow(w.id(), w.projectId(), w.name(), id, environments, w.machines(), w.dockerConnections(), w.resources(), bindings);
    }
    Workflow select(Workflow w, String id) { return new Workflow(w.id(), w.projectId(), w.name(), id, w.environments(), w.machines(), w.dockerConnections(), w.resources(), w.bindings()); }
    Workflow bind(Workflow w, String environment, String resource, Target target) {
        var machines = new ArrayList<>(w.machines());
        if (target.kind().equals("machine") && machines.stream().noneMatch(m -> m.id().equals(target.id()))) machines.add(new MachineReference(target.id()));
        var connections = new ArrayList<>(w.dockerConnections());
        if (target.kind().equals("docker") && connections.stream().noneMatch(c -> c.id().equals(target.id()))) {
            try { connections.add(workflows.containerOptions().stream().filter(o -> o.connection().id().equals(target.id())).findFirst().orElseThrow().connection()); }
            catch (java.io.IOException e) { throw new RuntimeException(e); }
        }
        return new Workflow(w.id(), w.projectId(), w.name(), w.defaultEnvironmentId(), w.environments(), machines, connections, w.resources(), w.bindings().stream().map(b -> b.resourceId().equals(resource) && b.environmentId().equals(environment) ? new Binding(b.id(), environment, resource, target) : b).toList());
    }
    @Test void environmentsPersistIndependentTargetsAndRemovalDoesNotTouchResources() throws Exception {
        var hosts = new HostRegistry(projects.directory()); var helfer = hosts.save(null, "Helfer", "fixture.invalid", "", null);
        byte[] source = Files.readAllBytes(projects.directory().resolve("projects.json"));
        var local = workflows.get(project); String localId = local.defaultEnvironmentId();
        var staging = bind(duplicate(local, "staging", "Staging"), "staging", service, new Target("machine", helfer.id()));
        staging = workflows.save(project, staging);
        assertEquals("unsupported", workflows.view(staging, hosts.view()).resources().getFirst().status());
        assertThrows(ApiException.class, () -> workflows.requireLocalService(project, service));
        var restored = new WorkflowStore(new ProjectStore(projects.directory())); assertEquals(staging, restored.get(project));
        restored.save(project, select(staging, localId)); assertDoesNotThrow(() -> restored.requireLocalService(project, service));
        var current = restored.get(project);
        var remaining = new Workflow(current.id(), project, current.name(), localId, List.of(new Environment(localId, "My Mac")), current.machines(), current.dockerConnections(), current.resources(), current.bindings().stream().filter(b -> b.environmentId().equals(localId)).toList());
        restored.save(project, remaining);
        assertArrayEquals(source, Files.readAllBytes(projects.directory().resolve("projects.json")));
        assertEquals(2, hosts.view().hosts().size()); assertTrue(fake.calls.isEmpty());
        assertEquals("My Mac", new WorkflowStore(projects).get(project).environments().getFirst().name());
    }
    @Test void containerControlsUseSelectedEnvironmentWithFullPinnedIdentity() throws Exception {
        var original = containers.add(project, "local", DockerServiceTest.LOCAL, DockerServiceTest.ID);
        String other = projects.addProject("Staging inventory", Files.createDirectory(temporary.resolve("other")).toString()).id();
        var remote = containers.add(other, "remote", DockerServiceTest.REMOTE, DockerServiceTest.ID);
        var local = workflows.get(project); String localEnv = local.defaultEnvironmentId();
        var option = workflows.containerOptions().stream().filter(o -> o.id().equals(remote.id())).findFirst().orElseThrow();
        var staging = bind(duplicate(local, "staging", "Staging"), "staging", original.id(), new Target("docker", option.connection().id(), remote.id()));
        workflows.save(project, staging); fake.calls.clear();
        assertEquals(1, workflows.effectiveContainers(project).size());
        var effective = workflows.effectiveContainers(project).getFirst();
        assertEquals(original.id(), effective.id()); assertEquals(remote.endpoint(), effective.endpoint()); assertEquals(remote.containerId(), effective.containerId());
        containers.action(effective, "stop");
        assertTrue(fake.calls.stream().anyMatch(c -> c.contains("stop") && c.contains("remote") && c.getLast().equals(DockerServiceTest.ID)));
        assertTrue(fake.calls.stream().noneMatch(c -> c.contains("create") || c.contains("rm")));
        var restored = new WorkflowStore(projects); assertEquals(staging, restored.get(project));
        restored.save(project, select(staging, localEnv)); assertEquals(original, restored.effectiveContainers(project).getFirst());
        assertEquals(remote, containers.list(other).getFirst());
    }
    @Test void inactiveBindingsProtectMachinesConnectionsAndSharedContainerLinks() throws Exception {
        var hosts = new HostRegistry(projects.directory()); var helfer = hosts.save(null, "Helfer", "fixture.invalid", "", null);
        var original = containers.add(project, "local", DockerServiceTest.LOCAL, DockerServiceTest.ID);
        String other = projects.addProject("Other", Files.createDirectory(temporary.resolve("other")).toString()).id();
        var remote = containers.add(other, "remote", DockerServiceTest.REMOTE, DockerServiceTest.ID);
        var local = workflows.get(project); String localEnv = local.defaultEnvironmentId();
        var option = workflows.containerOptions().stream().filter(o -> o.id().equals(remote.id())).findFirst().orElseThrow();
        var w = bind(duplicate(local, "staging", "Staging"), "staging", service, new Target("machine", helfer.id()));
        w = bind(w, "staging", original.id(), new Target("docker", option.connection().id(), remote.id()));
        workflows.save(project, select(w, localEnv));
        assertTrue(assertThrows(ApiException.class, () -> workflows.requireUnusedMachine(helfer.id())).getMessage().contains("Staging"));
        assertThrows(ApiException.class, () -> workflows.requireUnusedDocker("remote"));
        assertThrows(ApiException.class, () -> workflows.requireUnsharedLink(remote.id()));
        assertDoesNotThrow(() -> workflows.requireUnusedMachine("unbound"));
        // Simulate deletion outside Runtime Bay. A saved reference remains unresolved, never moved locally.
        containers.remove(other, remote.id());
        workflows.save(project, select(workflows.get(project), "staging"));
        var view = workflows.view(workflows.get(project), hosts.view());
        assertEquals("unresolved", view.resources().stream().filter(r -> r.resourceId().equals(original.id())).findFirst().orElseThrow().status());
        assertThrows(ApiException.class, () -> workflows.effectiveContainers(project));
    }
    @Test void duplicateEnvironmentNamesAndForgedDockerEndpointsAreRejectedAtomically() throws Exception {
        var w = workflows.get(project); Path file = projects.directory().resolve("workflows.json"); byte[] before = Files.readAllBytes(file);
        assertThrows(ApiException.class, () -> workflows.save(project, duplicate(workflows.get(project), "other", " LOCAL ")));
        assertArrayEquals(before, Files.readAllBytes(file));
        containers.add(project, "local", DockerServiceTest.LOCAL, DockerServiceTest.ID); w = workflows.get(project);
        var c = w.dockerConnections().getFirst();
        var forged = new Workflow(w.id(), project, w.name(), w.defaultEnvironmentId(), w.environments(), w.machines(), List.of(new DockerConnection(c.id(), c.context(), "ssh://attacker.invalid", null)), w.resources(), w.bindings());
        before = Files.readAllBytes(file); assertThrows(ApiException.class, () -> workflows.save(project, forged)); assertArrayEquals(before, Files.readAllBytes(file));
    }
    @Test void changedEnvironmentIsRejectedBeforeAnyAction() throws Exception {
        var local = workflows.get(project); String old = local.defaultEnvironmentId();
        workflows.save(project, duplicate(local, "staging", "Staging"));
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.requireSelection(project, old, null)).status);
        assertDoesNotThrow(() -> workflows.requireSelection(project, "staging", null)); assertTrue(fake.calls.isEmpty());
    }
}

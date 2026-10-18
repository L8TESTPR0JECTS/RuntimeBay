package dev.runtimebay;

import com.google.gson.Gson;
import dev.runtimebay.WorkflowModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowStoreTest {
    @TempDir Path temporary;
    ProjectStore projects;
    String project, service, second;
    @BeforeEach void setup() throws Exception {
        projects = new ProjectStore(temporary.resolve("data"));
        project = projects.addProject("Gobble", temporary.toString()).id();
        service = projects.saveService(project, null, "API", "printf original", ".", 4321).id();
        second = projects.saveService(project, null, "Worker", "printf worker", ".", null).id();
    }
    Workflow bind(Workflow original, String resource, String machine) {
        var machines = new ArrayList<>(original.machines());
        if (machines.stream().noneMatch(m -> m.id().equals(machine))) machines.add(new MachineReference(machine));
        return new Workflow(original.id(), original.projectId(), original.name(), original.defaultEnvironmentId(), original.environments(), machines, original.dockerConnections(), original.resources(), original.bindings().stream().map(b -> b.resourceId().equals(resource) ? new Binding(b.id(), b.environmentId(), b.resourceId(), new Target("machine", machine)) : b).toList());
    }
    @Test void migratesLegacyDefinitionsAndPinnedContainersWithoutChangingSources() throws Exception {
        String linkId = UUID.randomUUID().toString();
        var legacy = new WorkflowContainers.Registry(1, List.of(new WorkflowContainers.Link(linkId, project, "helfer-docker", "ssh://alek@helfer", "a".repeat(64), "Kafka", "apache/kafka")));
        Path links = projects.directory().resolve("workflow-containers.json");
        Files.writeString(links, new Gson().toJson(legacy));
        byte[] originalProjects = Files.readAllBytes(projects.directory().resolve("projects.json")), originalLinks = Files.readAllBytes(links);
        var store = new WorkflowStore(projects); var workflow = store.get(project);
        assertEquals(project, workflow.id()); assertEquals(3, workflow.resources().size());
        assertTrue(workflow.resources().stream().anyMatch(r -> r.id().equals(service) && r.sourceId().equals(service)));
        var container = workflow.resources().stream().filter(r -> r.id().equals(linkId)).findFirst().orElseThrow();
        assertEquals("external", container.ownership());
        var connection = workflow.dockerConnections().getFirst();
        assertEquals("helfer-docker", connection.context()); assertEquals("ssh://alek@helfer", connection.endpoint()); assertNull(connection.machineId());
        assertEquals("docker", workflow.bindings().stream().filter(b -> b.resourceId().equals(linkId)).findFirst().orElseThrow().target().kind());
        assertArrayEquals(originalProjects, Files.readAllBytes(projects.directory().resolve("projects.json"))); assertArrayEquals(originalLinks, Files.readAllBytes(links));
        assertEquals(workflow, new WorkflowStore(new ProjectStore(projects.directory())).get(project));
        assertEquals("printf original", projects.service(project, service).command());
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(projects.directory().resolve("workflows.json"))));
    }
    @Test void oneEnvironmentSpansMachinesWithoutFollowingPreferredTerminalHost() throws Exception {
        var hosts = new HostRegistry(projects.directory());
        var remote = hosts.save(null, "Helfer", "helfer", "alek", 22);
        var store = new WorkflowStore(projects); var workflow = store.get(project);
        var saved = store.save(project, bind(workflow, second, remote.id()));
        hosts.makeDefault(remote.id());
        var restored = new WorkflowStore(new ProjectStore(projects.directory()));
        assertEquals(saved, restored.get(project));
        assertDoesNotThrow(() -> restored.requireLocalService(project, service));
        assertEquals(409, assertThrows(ApiException.class, () -> restored.requireLocalService(project, second)).status);
        var views = restored.view(saved, hosts.view()).resources();
        assertEquals("local", views.stream().filter(r -> r.resourceId().equals(service)).findFirst().orElseThrow().status());
        assertEquals("Helfer", views.stream().filter(r -> r.resourceId().equals(second)).findFirst().orElseThrow().targetName());
        hosts.makeDefault("local"); hosts.remove(remote.id());
        var missing = restored.view(restored.get(project), hosts.view()).resources().stream().filter(r -> r.resourceId().equals(second)).findFirst().orElseThrow();
        assertEquals("unresolved", missing.status()); assertEquals(remote.id(), missing.targetId());
        assertEquals(409, assertThrows(ApiException.class, () -> restored.requireLocalService(project, second)).status);
    }
    @Test void serviceEditsReconcileWithoutChangingBindingsOrCreatingNewIdentities() throws Exception {
        var store = new WorkflowStore(projects); var original = store.get(project);
        store.save(project, bind(original, second, "missing-host"));
        projects.saveService(project, service, "Renamed API", "echo changed", ".", null);
        var added = projects.saveService(project, null, "Third", "echo third", ".", null);
        var current = store.get(project);
        assertEquals(original.environments(), current.environments());
        assertEquals(original.bindings().stream().filter(b -> b.resourceId().equals(service)).findFirst().orElseThrow(), current.bindings().stream().filter(b -> b.resourceId().equals(service)).findFirst().orElseThrow());
        assertEquals("Renamed API", current.resources().stream().filter(r -> r.id().equals(service)).findFirst().orElseThrow().name());
        assertDoesNotThrow(() -> store.requireLocalService(project, added.id()));
        assertThrows(ApiException.class, () -> store.requireLocalService(project, second));
        projects.removeService(project, service); assertTrue(store.get(project).resources().stream().noneMatch(r -> r.id().equals(service)));
    }
    @Test void malformedMigrationAndGraphFilesAreNeverOverwritten() throws Exception {
        Path source = projects.directory().resolve("workflow-containers.json");
        Files.writeString(source, "broken");
        byte[] original = Files.readAllBytes(projects.directory().resolve("projects.json"));
        assertThrows(java.io.IOException.class, () -> new WorkflowStore(projects)); assertEquals("broken", Files.readString(source));
        assertFalse(Files.exists(projects.directory().resolve("workflows.json"))); assertArrayEquals(original, Files.readAllBytes(projects.directory().resolve("projects.json")));
        Files.delete(source); var store = new WorkflowStore(projects); store.get(project);
        Path graph = projects.directory().resolve("workflows.json"); Files.writeString(graph, "{\"version\":999,\"workflows\":[]}");
        assertThrows(java.io.IOException.class, () -> new WorkflowStore(projects)); assertEquals("{\"version\":999,\"workflows\":[]}", Files.readString(graph));
    }
    @Test void invalidOrMissingBindingsDoNotOverwriteTheGraph() throws Exception {
        var store = new WorkflowStore(projects); var workflow = store.get(project); Path file = projects.directory().resolve("workflows.json"); byte[] before = Files.readAllBytes(file);
        var missing = new Workflow(workflow.id(), project, workflow.name(), workflow.defaultEnvironmentId(), workflow.environments(), workflow.machines(), workflow.dockerConnections(), workflow.resources(), List.of());
        assertThrows(ApiException.class, () -> store.save(project, missing)); assertArrayEquals(before, Files.readAllBytes(file));
        var wrongKind = new Workflow(workflow.id(), project, workflow.name(), workflow.defaultEnvironmentId(), workflow.environments(), workflow.machines(), workflow.dockerConnections(), workflow.resources(), workflow.bindings().stream().map(b -> new Binding(b.id(), b.environmentId(), b.resourceId(), new Target("docker", "local"))).toList());
        assertThrows(ApiException.class, () -> store.save(project, wrongKind)); assertArrayEquals(before, Files.readAllBytes(file));
    }
    @Test void duplicateLegacyIdentitiesAreRejectedBeforeAnyMigrationWrite() throws Exception {
        Path file = projects.directory().resolve("projects.json");
        var original = projects.project(project);
        Files.writeString(file, new Gson().toJson(new dev.runtimebay.Models.Registry(1, List.of(original, original))));
        byte[] before = Files.readAllBytes(file);
        var duplicates = new ProjectStore(projects.directory());
        assertThrows(java.io.IOException.class, () -> new WorkflowStore(duplicates));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertFalse(Files.exists(projects.directory().resolve("workflows.json")));
    }
    @Test void remoteBindingsFailBeforeLocalProcessLaunch() throws Exception {
        var store = new WorkflowStore(projects); store.save(project, bind(store.get(project), service, "missing-host"));
        try (var runtimes = new RuntimeManager(projects, Path.of("/does-not-exist"))) {
            var error = assertThrows(ApiException.class, () -> runtimes.start(project, service));
            assertEquals(409, error.status); assertTrue(error.getMessage().contains("will not run it on This Mac"));
            assertEquals("idle", runtimes.projects().getFirst().services().getFirst().state());
        }
    }
}

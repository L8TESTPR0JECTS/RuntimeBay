package dev.runtimebay;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowAttachmentsTest {
    @TempDir Path temporary;
    ProjectStore projects;
    WorkflowStore workflows;
    WorkflowAttachments attachments;
    HostRegistry hosts;
    DatabaseWorkspace databases;
    String first, second, service, environment;
    @BeforeEach void setup() throws Exception {
        projects = new ProjectStore(temporary.resolve("data"));
        first = projects.addProject("Application", temporary.toString()).id();
        second = projects.addProject("Dependencies", Files.createDirectory(temporary.resolve("other")).toString()).id();
        service = projects.saveService(second, null, "Shared API", "echo do-not-run", ".", null).id();
        hosts = new HostRegistry(projects.directory()); workflows = new WorkflowStore(projects); attachments = new WorkflowAttachments(projects, workflows);
        environment = workflows.get(first).defaultEnvironmentId();
        databases = new DatabaseWorkspace(projects, (p, d, prefix, theme, progress) -> { fail("Attaching must not launch a database companion"); return null; }, "http://127.0.0.1:1");
    }
    @AfterEach void close() { databases.close(); }
    WorkflowAttachments.Source serviceSource() { return new WorkflowAttachments.Source("service", second, service, ""); }
    @Test void sharedServiceReferencePersistsWithoutCopyingOrOwningItsRuntime() throws Exception {
        byte[] before = Files.readAllBytes(projects.directory().resolve("projects.json"));
        var entry = attachments.attach(first, environment, serviceSource(), hosts.view(), List.of());
        assertEquals(entry, attachments.attach(first, environment, serviceSource(), hosts.view(), List.of()));
        var restored = new WorkflowAttachments(new ProjectStore(projects.directory()), workflows);
        var view = restored.view(first, hosts.view(), databases, Set.of()).getFirst();
        assertEquals(entry, view.reference()); assertEquals("managed-at-source", view.ownership());
        assertEquals(List.of("inspect-service", "rebind", "unlink"), view.actions());
        assertFalse(view.actions().contains("start")); assertFalse(view.actions().contains("stop"));
        assertEquals(0, projects.project(first).services().size()); assertEquals(1, projects.project(second).services().size());
        restored.unlink(first, entry.id()); assertTrue(restored.view(first, hosts.view(), databases, Set.of()).isEmpty());
        assertArrayEquals(before, Files.readAllBytes(projects.directory().resolve("projects.json")));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(projects.directory().resolve("workflow-attachments.json"))));
    }
    @Test void missingSourceRequiresExplicitReplacementAndRetainsAssociationIdentity() throws Exception {
        var entry = attachments.attach(first, environment, serviceSource(), hosts.view(), List.of());
        projects.removeService(second, service);
        var missing = attachments.view(first, hosts.view(), databases, Set.of()).getFirst();
        assertEquals("missing", missing.state()); assertEquals(entry.id(), missing.reference().id());
        assertEquals(List.of("rebind", "unlink"), missing.actions());
        var replacement = projects.saveService(second, null, "Shared API", "echo replacement", ".", null);
        assertEquals("missing", attachments.view(first, hosts.view(), databases, Set.of()).getFirst().state(), "A reused name must never retarget the reference");
        var rebound = attachments.rebind(first, entry.id(), new WorkflowAttachments.Source("service", second, replacement.id(), ""), hosts.view(), List.of());
        assertEquals(entry.id(), rebound.id()); assertEquals(replacement.id(), rebound.source().sourceId());
        assertEquals("saved", attachments.view(first, hosts.view(), databases, Set.of()).getFirst().state());
    }
    @Test void hostFailureIsActionableAndMissingHostNeverFallsBackToLocal() throws Exception {
        var host = hosts.save(null, "Helfer", "fixture.invalid", "", null);
        var source = new WorkflowAttachments.Source("terminal", "", host.id(), "");
        var entry = attachments.attach(first, environment, source, hosts.view(), List.of());
        assertThrows(ApiException.class, () -> attachments.protectHost(host.id()));
        var offline = attachments.view(first, hosts.view(), databases, Set.of(host.id())).getFirst();
        assertEquals("unavailable", offline.state()); assertTrue(offline.detail().contains("sign in")); assertEquals(entry, offline.reference());
        hosts.remove(host.id());
        var missing = attachments.view(first, hosts.view(), databases, Set.of()).getFirst(); assertEquals("missing", missing.state()); assertFalse(missing.actions().contains("open-terminal"));
        assertEquals(host.id(), missing.reference().source().sourceId());
    }
    @Test void databaseSnapshotsContainOnlyNamesAndIdsAndClosedCompanionsRemainUnstarted() throws Exception {
        var connection = new DatabaseWorkspace.SavedConnection("general", "mysql-stable-id", "Gobble MySQL");
        attachments.refreshDatabase(second, List.of(connection));
        var source = new WorkflowAttachments.Source("database", second, connection.id(), connection.scope());
        var entry = attachments.attach(first, environment, source, hosts.view(), List.of());
        var view = attachments.view(first, hosts.view(), databases, Set.of()).getFirst();
        assertEquals("unavailable", view.state()); assertEquals(List.of("open-database-workspace", "rebind", "unlink"), view.actions());
        assertEquals("closed", databases.status(second).state());
        attachments.refreshDatabase(second, List.of());
        assertEquals("missing", attachments.view(first, hosts.view(), databases, Set.of()).getFirst().state());
        assertEquals(entry.id(), attachments.view(first, hosts.view(), databases, Set.of()).getFirst().reference().id());
        attachments.refreshDatabase(second, List.of(new DatabaseWorkspace.SavedConnection("general", "different-id", "Gobble MySQL")));
        assertEquals("missing", attachments.view(first, hosts.view(), databases, Set.of()).getFirst().state());
        attachments.unlink(first, entry.id()); assertEquals("closed", databases.status(second).state());
    }
    @Test void environmentAndWorkflowBoundariesAreEnforcedWithoutDeletingSources() throws Exception {
        var entry = attachments.attach(first, environment, serviceSource(), hosts.view(), List.of());
        var w = workflows.get(first);
        var other = new WorkflowModels.Environment("staging", "Staging");
        var environments = new ArrayList<>(w.environments()); environments.add(other);
        var updated = new WorkflowModels.Workflow(w.id(), w.projectId(), w.name(), other.id(), environments, w.machines(), w.dockerConnections(), w.resources(), w.bindings());
        workflows.save(first, updated); assertTrue(attachments.view(first, hosts.view(), databases, Set.of()).isEmpty());
        assertThrows(ApiException.class, () -> attachments.unlink(first, entry.id()));
        assertThrows(ApiException.class, () -> attachments.unlink(second, entry.id()));
        var removed = new WorkflowModels.Workflow(w.id(), w.projectId(), w.name(), other.id(), List.of(other), w.machines(), w.dockerConnections(), w.resources(), List.of());
        assertThrows(ApiException.class, () -> attachments.protectEnvironments(first, removed));
        assertEquals(service, projects.service(second, service).id());
    }
    @Test void corruptRegistriesAndInvalidConnectionSnapshotsArePreserved() throws Exception {
        Path file = projects.directory().resolve("workflow-attachments.json");
        attachments.refreshDatabase(second, List.of()); byte[] before = Files.readAllBytes(file);
        var bad = new DatabaseWorkspace.SavedConnection("general", "id", "bad\nname");
        assertThrows(IllegalArgumentException.class, () -> attachments.refreshDatabase(second, List.of(bad))); assertArrayEquals(before, Files.readAllBytes(file));
        Files.writeString(file, "broken-registry"); assertThrows(java.io.IOException.class, () -> new WorkflowAttachments(projects, workflows)); assertEquals("broken-registry", Files.readString(file));
    }
    @Test void sharedContainerChoicesUsePhysicalIdentityRatherThanDuplicatingTheDependency() {
        String id = "a".repeat(64);
        var a = new WorkflowContainers.Link("first-link", first, "remote", "ssh://fixture.invalid", id, "Kafka", "kafka");
        var b = new WorkflowContainers.Link("second-link", second, a.context(), a.endpoint(), id, a.name(), a.image());
        assertEquals(1, attachments.choices(hosts.view(), List.of(a, b)).stream().filter(c -> c.source().kind().equals("container")).count());
    }
}

package dev.runtimebay;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowContainersTest {
    @TempDir Path temporary;
    ProjectStore projects;
    DockerServiceTest.FakeDocker fixture;
    DockerService docker;
    WorkflowContainers workflows;
    String project;
    @BeforeEach void setup() throws Exception {
        projects = new ProjectStore(temporary.resolve("data")); project = projects.addProject("Gobble fixture", temporary.toString()).id();
        fixture = new DockerServiceTest.FakeDocker(); docker = new DockerService(fixture); workflows = new WorkflowContainers(projects, docker);
    }
    @AfterEach void close() { docker.close(); }
    WorkflowContainers.Link add() throws Exception { return workflows.add(project, "remote", DockerServiceTest.REMOTE, DockerServiceTest.ID); }
    @Test void linksExistingContainerWithoutChangingItAndPersistsAcrossReloads() throws Exception {
        var link = add(); assertEquals("api", link.name()); assertEquals("remote", link.context());
        assertEquals(1, workflows.list(project).size()); assertEquals(link, add());
        var restored = new WorkflowContainers(new ProjectStore(projects.directory()), docker);
        assertEquals(List.of(link), restored.list(project));
        assertEquals("running", workflows.status(project).getFirst().state());
        assertTrue(fixture.calls.stream().noneMatch(call -> call.contains("create") || call.contains("start") || call.contains("stop") || call.contains("rm")));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(projects.directory().resolve("workflow-containers.json"))));
    }
    @Test void pinsHostEndpointFullContainerIdentityAndProjectMembership() throws Exception {
        assertThrows(ApiException.class, () -> workflows.add(project, "remote", "ssh://wrong.example.com", DockerServiceTest.ID));
        assertThrows(ApiException.class, () -> workflows.add(project, "remote", DockerServiceTest.REMOTE, "short"));
        assertThrows(ApiException.class, () -> workflows.add(project, "remote", DockerServiceTest.REMOTE, "a".repeat(64)));
        var link = add(); String second = projects.addProject("Other", Files.createDirectory(temporary.resolve("other")).toString()).id();
        assertThrows(ApiException.class, () -> workflows.action(second, link.id(), "stop"));
        assertThrows(ApiException.class, () -> workflows.remove(second, link.id()));
        assertThrows(ApiException.class, () -> workflows.action(project, link.id(), "remove"));
        fixture.contexts.put("remote", DockerServiceTest.FakeDocker.context("remote", "ssh://changed.example.com", false, false));
        assertThrows(ApiException.class, () -> workflows.action(project, link.id(), "stop")); assertTrue(fixture.running);
        assertEquals("unavailable", workflows.status(project).getFirst().state());
    }
    @Test void workflowControlsOnlyTheLinkedContainerAndUnlinkNeverRemovesIt() throws Exception {
        var link = add(); workflows.action(project, link.id(), "stop"); assertFalse(fixture.running);
        workflows.action(project, link.id(), "start"); assertTrue(fixture.running);
        workflows.action(project, link.id(), "resume");
        assertTrue(fixture.calls.stream().anyMatch(call -> call.contains("unpause") && call.getLast().equals(DockerServiceTest.ID)));
        fixture.calls.clear(); workflows.remove(project, link.id());
        assertTrue(workflows.list(project).isEmpty()); assertTrue(fixture.calls.isEmpty()); assertTrue(fixture.running);
        assertTrue(new WorkflowContainers(projects, docker).list(project).isEmpty());
    }
    @Test void missingContainerRemainsLinkedAndCanBeUnlinkedOffline() throws Exception {
        var link = add(); fixture.containerMissing = true;
        assertEquals("missing", workflows.status(project).getFirst().state());
        assertEquals(List.of(link), workflows.list(project));
        fixture.contexts.remove("remote");
        assertEquals("unavailable", workflows.status(project).getFirst().state());
        workflows.remove(project, link.id()); assertTrue(workflows.list(project).isEmpty());
    }
    @Test void preservesMalformedRegistryRatherThanOverwritingIt() throws Exception {
        Path file = projects.directory().resolve("workflow-containers.json"); Files.writeString(file, "broken");
        assertThrows(java.io.IOException.class, () -> new WorkflowContainers(projects, docker)); assertEquals("broken", Files.readString(file));
    }
}

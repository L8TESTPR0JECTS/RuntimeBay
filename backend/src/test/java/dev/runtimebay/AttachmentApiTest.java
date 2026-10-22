package dev.runtimebay;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentApiTest {
    @TempDir Path temporary;
    ProjectStore projects;
    RuntimeManager runtimes;
    ApiServer server;
    DockerService docker;
    DockerServiceTest.FakeDocker fake;
    String project, sourceProject, service;
    final String token = "s".repeat(64);
    final Gson gson = new Gson();
    final HttpClient client = HttpClient.newHttpClient();
    @BeforeEach void setup() throws Exception {
        projects = new ProjectStore(temporary.resolve("data")); project = projects.addProject("Application", temporary.toString()).id();
        sourceProject = projects.addProject("Shared resources", Files.createDirectory(temporary.resolve("source")).toString()).id();
        service = projects.saveService(sourceProject, null, "API", "echo must-not-run", ".", null).id();
        runtimes = new RuntimeManager(projects, Path.of("/not-used")); fake = new DockerServiceTest.FakeDocker(); docker = new DockerService(fake);
        Path web = Files.createDirectory(temporary.resolve("web")); Files.writeString(web.resolve("index.html"), "fixture");
        server = new ApiServer(projects, runtimes, web, token, docker); server.start();
    }
    @AfterEach void close() { server.close(); runtimes.close(); docker.close(); }
    String path() { return "/workflows/" + project + "/attachments"; }
    HttpResponse<String> call(String path, String method, Object body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api" + path)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    Map<String, Object> body(WorkflowAttachments.Source source) throws Exception {
        var workflow = runtimes.workflows().get(project);
        var result = new HashMap<String, Object>(); result.put("environmentId", workflow.defaultEnvironmentId()); result.put("workflowRevision", runtimes.workflows().revision(workflow)); if (source != null) result.put("source", source); return result;
    }
    Map<String, Object> referenceBody(WorkflowAttachments.Source previous, WorkflowAttachments.Source replacement) throws Exception {
        var body = body(replacement); body.put("referenceKey", WorkflowAttachments.key(previous)); return body;
    }
    WorkflowAttachments.Source serviceSource() { return new WorkflowAttachments.Source("service", sourceProject, service, ""); }
    @Test void authenticationAndOriginChecksPrecedeCatalogAndReferenceMutations() throws Exception {
        var uri = URI.create("http://127.0.0.1:" + server.port() + "/api" + path());
        assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(uri + "/catalog")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, client.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token).header("Origin", "https://outside.invalid").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body(serviceSource())))).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertFalse(Files.exists(projects.directory().resolve("workflow-attachments.json"))); assertTrue(fake.calls.isEmpty());
    }
    @Test void attachingAndUnlinkingAServiceDoesNotCopyOrLaunchIt() throws Exception {
        byte[] before = Files.readAllBytes(projects.directory().resolve("projects.json"));
        var response = call(path(), "POST", body(serviceSource())); assertEquals(201, response.statusCode(), response.body());
        String id = JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsString();
        var refs = JsonParser.parseString(call(path(), "GET", null).body()).getAsJsonObject().getAsJsonArray("resources");
        assertEquals("managed-at-source", refs.get(0).getAsJsonObject().get("ownership").getAsString());
        assertEquals(200, call(path() + "/" + id + "/resolve", "POST", referenceBody(serviceSource(), null)).statusCode());
        assertEquals(200, call(path() + "/" + id, "DELETE", referenceBody(serviceSource(), null)).statusCode());
        assertArrayEquals(before, Files.readAllBytes(projects.directory().resolve("projects.json"))); assertTrue(fake.calls.isEmpty());
        assertEquals("idle", runtimes.projects().stream().filter(p -> p.id().equals(sourceProject)).findFirst().orElseThrow().services().getFirst().state());
    }
    @Test void sharedContainersKeepTheirFullIdentityAndUnlinkNeverRemovesThePhysicalContainer() throws Exception {
        var linked = call("/projects/" + sourceProject + "/containers", "POST", Map.of("context", "remote", "endpoint", DockerServiceTest.REMOTE, "containerId", DockerServiceTest.ID)); assertEquals(201, linked.statusCode());
        String originalLink = JsonParser.parseString(linked.body()).getAsJsonObject().get("id").getAsString();
        fake.calls.clear(); var shared = call(path(), "POST", body(new WorkflowAttachments.Source("container", sourceProject, originalLink, "")));
        assertEquals(201, shared.statusCode(), shared.body());
        var target = JsonParser.parseString(shared.body()).getAsJsonObject(); assertEquals(DockerServiceTest.ID, target.get("containerId").getAsString()); assertEquals(DockerServiceTest.REMOTE, target.get("endpoint").getAsString());
        assertTrue(fake.calls.isEmpty()); assertTrue(fake.running);
        String localLink = target.get("id").getAsString(); assertNotEquals(originalLink, localLink);
        assertEquals(200, call("/projects/" + project + "/containers/" + localLink, "DELETE", null).statusCode());
        assertEquals(1, JsonParser.parseString(call("/projects/" + sourceProject + "/containers/links", "GET", null).body()).getAsJsonArray().size());
        assertTrue(fake.calls.isEmpty()); assertTrue(fake.running);
    }
    @Test void terminalIntentsProtectHostsAndResolveWithoutOpeningASession() throws Exception {
        var hostResponse = call("/hosts", "POST", Map.of("name", "Helfer", "address", "fixture.invalid", "user", "alek"));
        String host = JsonParser.parseString(hostResponse.body()).getAsJsonObject().get("id").getAsString();
        var attached = call(path(), "POST", body(new WorkflowAttachments.Source("terminal", "", host, ""))); assertEquals(201, attached.statusCode());
        String id = JsonParser.parseString(attached.body()).getAsJsonObject().get("id").getAsString();
        assertEquals(409, call("/hosts/" + host, "DELETE", null).statusCode());
        assertEquals(409, call("/hosts/" + host, "PUT", Map.of("name", "Helfer", "address", "changed.invalid", "user", "alek")).statusCode());
        assertEquals(200, call(path() + "/" + id + "/resolve", "POST", referenceBody(new WorkflowAttachments.Source("terminal", "", host, ""), null)).statusCode());
        assertEquals(0, JsonParser.parseString(call("/terminals", "GET", null).body()).getAsJsonArray().size());
        assertEquals(200, call(path() + "/" + id, "PUT", referenceBody(new WorkflowAttachments.Source("terminal", "", host, ""), new WorkflowAttachments.Source("terminal", "", "local", ""))).statusCode());
        assertEquals(409, call(path() + "/" + id + "/resolve", "POST", referenceBody(new WorkflowAttachments.Source("terminal", "", host, ""), null)).statusCode());
        assertEquals(200, call("/hosts/" + host, "DELETE", null).statusCode()); assertTrue(fake.calls.isEmpty());
    }
    @Test void attachedEnvironmentsCannotBeRemovedAndInactiveReferenceActionsAreRejected() throws Exception {
        var attached = call(path(), "POST", body(serviceSource())); String id = JsonParser.parseString(attached.body()).getAsJsonObject().get("id").getAsString();
        var w = runtimes.workflows().get(project);
        var environments = new ArrayList<>(w.environments()); environments.add(new WorkflowModels.Environment("staging", "Staging"));
        var next = new WorkflowModels.Workflow(project, project, w.name(), "staging", environments, w.machines(), w.dockerConnections(), w.resources(), w.bindings());
        assertEquals(200, call("/workflows/" + project, "PUT", next).statusCode());
        assertEquals(409, call(path() + "/" + id, "DELETE", referenceBody(serviceSource(), null)).statusCode());
        var removed = new WorkflowModels.Workflow(project, project, w.name(), "staging", List.of(new WorkflowModels.Environment("staging", "Staging")), w.machines(), w.dockerConnections(), w.resources(), List.of());
        assertEquals(409, call("/workflows/" + project, "PUT", removed).statusCode());
        assertEquals(2, runtimes.workflows().get(project).environments().size());
    }
    @Test void staleContextsMissingSourcesAndClosedDatabaseDiscoveryFailWithoutMutation() throws Exception {
        var request = body(serviceSource()); request.put("workflowRevision", "stale");
        assertEquals(409, call(path(), "POST", request).statusCode());
        assertEquals(400, call(path(), "POST", Map.of()).statusCode());
        var attached = call(path(), "POST", body(serviceSource())); String id = JsonParser.parseString(attached.body()).getAsJsonObject().get("id").getAsString();
        projects.removeService(sourceProject, service);
        var missing = call(path() + "/" + id + "/resolve", "POST", referenceBody(serviceSource(), null)); assertEquals(409, missing.statusCode());
        byte[] references = Files.readAllBytes(projects.directory().resolve("workflow-attachments.json"));
        assertEquals(409, call(path() + "/databases/" + sourceProject, "POST", Map.of()).statusCode());
        assertArrayEquals(references, Files.readAllBytes(projects.directory().resolve("workflow-attachments.json")));
        assertTrue(fake.calls.isEmpty());
    }
}

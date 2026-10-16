package dev.runtimebay;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class DockerApiTest {
    @TempDir Path temporary;
    ApiServer server;
    RuntimeManager runtime;
    DockerService docker;
    ProjectStore store;
    DockerServiceTest.FakeDocker fixture;
    String token = "t".repeat(64);
    HttpClient client = HttpClient.newHttpClient();
    @BeforeEach void setup() throws Exception {
        Path web = Files.createDirectory(temporary.resolve("web")); Files.writeString(web.resolve("index.html"), "Runtime Bay");
        store = new ProjectStore(temporary.resolve("data"));
        runtime = new RuntimeManager(store, Path.of("../.build/runtimebay-launcher"));
        fixture = new DockerServiceTest.FakeDocker(); docker = new DockerService(fixture);
        server = new ApiServer(store, runtime, web, token, docker); server.start();
    }
    @AfterEach void cleanup() { if (server != null) server.close(); if (docker != null) docker.close(); if (runtime != null) runtime.close(); }
    HttpRequest.Builder request(String path, boolean authenticated) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/docker" + path)).timeout(Duration.ofSeconds(5));
        if (authenticated) builder.header("Authorization", "Bearer " + token); return builder;
    }
    HttpResponse<String> get(String path, boolean auth) throws Exception { return client.send(request(path, auth).build(), HttpResponse.BodyHandlers.ofString()); }
    HttpResponse<String> post(String path, String json) throws Exception { return client.send(request(path, true).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString()); }
    @Test void authenticatesContainerEndpointsAndRejectsCrossOriginCommands() throws Exception {
        assertEquals(401, get("/environments", false).statusCode());
        assertTrue(fixture.calls.isEmpty());
        assertEquals(200, get("/environments", true).statusCode());
        var rejected = client.send(request("/environments/remote/containers/" + DockerServiceTest.ID + "/stop", true).header("Origin", "https://example.com").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"endpoint\":\"" + DockerServiceTest.REMOTE + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, rejected.statusCode());
        assertTrue(fixture.running);
    }
    @Test void usesEndpointSnapshotAndValidatesContainerIdsAndPorts() throws Exception {
        String query = "?endpoint=" + URLEncoder.encode(DockerServiceTest.REMOTE, StandardCharsets.UTF_8);
        var overview = get("/environments/remote/overview" + query, true);
        assertEquals(200, overview.statusCode()); assertTrue(overview.body().contains("fixture-engine"));
        assertEquals(409, post("/environments/remote/containers/" + DockerServiceTest.ID + "/stop", "{\"endpoint\":\"ssh://changed.example.com\"}").statusCode());
        assertEquals(400, post("/environments/remote/containers/--all/stop", "{\"endpoint\":\"" + DockerServiceTest.REMOTE + "\"}").statusCode());
        assertEquals(400, post("/environments/remote/containers", "{\"endpoint\":\"" + DockerServiceTest.REMOTE + "\",\"definition\":{\"name\":\"fraction\",\"image\":\"nginx:alpine\",\"ports\":[{\"host\":8080.5,\"container\":80}]}}").statusCode());
    }
    @Test void editsHostsWithAuthenticationOriginChecksAndEndpointSnapshots() throws Exception {
        String body = "{\"expectedEndpoint\":\"" + DockerServiceTest.REMOTE + "\",\"endpoint\":\"ssh://alek@helfer:2222\",\"description\":\"Helfer\"}";
        for (boolean authenticated : new boolean[]{false, true}) {
            var builder = request("/environments/remote", authenticated).header("Content-Type", "application/json");
            if (authenticated) builder.header("Origin", "https://example.com");
            assertEquals(authenticated ? 403 : 401, client.send(builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertEquals(DockerServiceTest.REMOTE, fixture.contexts.get("remote").getAsJsonObject("Endpoints").getAsJsonObject("docker").get("Host").getAsString());
        var updated = client.send(request("/environments/remote", true).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, updated.statusCode()); assertTrue(updated.body().contains("ssh://alek@helfer:2222"));
        assertEquals(409, client.send(request("/environments/remote", true).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void workflowContainerEndpointsAuthenticatePersistAndUnlinkWithoutDockerMutation() throws Exception {
        String project = store.addProject("Workflow fixture", temporary.toString()).id();
        String path = "http://127.0.0.1:" + server.port() + "/api/projects/" + project + "/containers";
        String body = "{\"context\":\"remote\",\"endpoint\":\"" + DockerServiceTest.REMOTE + "\",\"containerId\":\"" + DockerServiceTest.ID + "\"}";
        var builder = HttpRequest.newBuilder(URI.create(path)).header("Content-Type", "application/json");
        assertEquals(401, client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertTrue(fixture.calls.isEmpty());
        var response = client.send(HttpRequest.newBuilder(URI.create(path)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode());
        String id = JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsString();
        assertEquals(403, client.send(HttpRequest.newBuilder(URI.create(path + "/" + id + "/stop")).header("Authorization", "Bearer " + token).header("Origin", "https://example.com").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertTrue(fixture.running); fixture.calls.clear();
        var listed = client.send(HttpRequest.newBuilder(URI.create(path)).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listed.statusCode()); assertTrue(listed.body().contains(id));
        assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(path + "/" + id)).header("Authorization", "Bearer " + token).DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertTrue(fixture.calls.isEmpty()); assertTrue(fixture.running);
    }
    @Test void creatingAContainerWithAWorkflowAlsoSavesTheLink() throws Exception {
        String project = store.addProject("Workflow fixture", temporary.toString()).id();
        var created = post("/environments/remote/containers", "{\"endpoint\":\"" + DockerServiceTest.REMOTE + "\",\"definition\":{\"name\":\"workflow-api\",\"image\":\"nginx:alpine\",\"projectId\":\"" + project + "\",\"ports\":[],\"mounts\":[],\"arguments\":[]}}");
        assertEquals(201, created.statusCode());
        var links = new WorkflowContainers(store, docker).list(project);
        assertEquals(1, links.size()); assertEquals("workflow-api", links.getFirst().name());
        assertEquals(DockerServiceTest.ID, links.getFirst().containerId()); assertEquals(DockerServiceTest.REMOTE, links.getFirst().endpoint());
    }
    @Test void createsContainerAndStartsAnAsynchronousPull() throws Exception {
        var result = post("/environments/remote/containers", "{\"endpoint\":\"" + DockerServiceTest.REMOTE + "\",\"definition\":{\"name\":\"api\",\"image\":\"nginx:alpine\",\"ports\":[],\"mounts\":[],\"arguments\":[]}}");
        assertEquals(201, result.statusCode()); assertTrue(result.body().contains(DockerServiceTest.ID));
        var pull = post("/environments/remote/images/pull", "{\"endpoint\":\"" + DockerServiceTest.REMOTE + "\",\"image\":\"nginx:alpine\"}");
        assertEquals(202, pull.statusCode());
        String id = JsonParser.parseString(pull.body()).getAsJsonObject().get("id").getAsString();
        assertEquals(200, get("/operations/" + id, true).statusCode());
        assertEquals(200, get("/operations", true).statusCode());
    }
}

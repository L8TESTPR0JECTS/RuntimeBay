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
    DockerServiceTest.FakeDocker fixture;
    String token = "t".repeat(64);
    HttpClient client = HttpClient.newHttpClient();
    @BeforeEach void setup() throws Exception {
        Path web = Files.createDirectory(temporary.resolve("web")); Files.writeString(web.resolve("index.html"), "Runtime Bay");
        ProjectStore store = new ProjectStore(temporary.resolve("data"));
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

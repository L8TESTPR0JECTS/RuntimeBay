package dev.runtimebay;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentApiTest {
    @TempDir Path temporary;
    ProjectStore projects;
    RuntimeManager runtimes;
    ApiServer server;
    DockerService docker;
    DockerServiceTest.FakeDocker fake;
    String project, service;
    final String token = "e".repeat(64);
    HttpClient client = HttpClient.newHttpClient();
    @BeforeEach void setup() throws Exception {
        projects = new ProjectStore(temporary.resolve("data")); project = projects.addProject("Gobble", temporary.toString()).id();
        service = projects.saveService(project, null, "API", "printf test", ".", null).id();
        runtimes = new RuntimeManager(projects, Path.of("/not-used")); fake = new DockerServiceTest.FakeDocker(); docker = new DockerService(fake);
        Path web = Files.createDirectory(temporary.resolve("web")); Files.writeString(web.resolve("index.html"), "test");
        server = new ApiServer(projects, runtimes, web, token, docker); server.start();
    }
    @AfterEach void close() { server.close(); runtimes.close(); docker.close(); }
    HttpResponse<String> call(String path, String method, Object body, String revision) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api" + path)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        if (revision != null) request.header("If-Match", revision);
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(new Gson().toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonObject view() throws Exception { return JsonParser.parseString(call("/workflows/" + project, "GET", null, null).body()).getAsJsonObject(); }
    @Test void staleEditorsCannotOverwriteNewerEnvironmentDefinitions() throws Exception {
        var view = view(); var definition = view.getAsJsonObject("definition"); String original = view.get("revision").getAsString();
        definition.getAsJsonArray("environments").get(0).getAsJsonObject().addProperty("name", "Development");
        var response = call("/workflows/" + project, "PUT", definition, original); assertEquals(200, response.statusCode(), response.body());
        definition.getAsJsonArray("environments").get(0).getAsJsonObject().addProperty("name", "Stale edit");
        assertEquals(409, call("/workflows/" + project, "PUT", definition, original).statusCode());
        assertEquals("Development", runtimes.workflows().get(project).environments().getFirst().name());
        assertEquals(409, call("/projects/" + project + "/services/" + service + "/start", "POST", Map.of("workflowRevision", original), null).statusCode());
        assertTrue(fake.calls.isEmpty());
    }
    @Test void hostRemovalAndEndpointChangesMustResolveBindingsFirst() throws Exception {
        var created = call("/hosts", "POST", Map.of("name", "Helfer", "address", "fixture.invalid", "user", "alek"), null);
        assertEquals(201, created.statusCode(), created.body());
        String host = JsonParser.parseString(created.body()).getAsJsonObject().get("id").getAsString();
        var definition = view().getAsJsonObject("definition"); var machine = new JsonObject(); machine.addProperty("id", host); definition.getAsJsonArray("machines").add(machine);
        definition.getAsJsonArray("bindings").get(0).getAsJsonObject().getAsJsonObject("target").addProperty("id", host);
        assertEquals(200, call("/workflows/" + project, "PUT", definition, null).statusCode());
        assertEquals(409, call("/hosts/" + host, "DELETE", null, null).statusCode());
        assertEquals(409, call("/hosts/" + host, "PUT", Map.of("name", "Helfer", "address", "changed.invalid", "user", "alek"), null).statusCode());
        assertEquals(200, call("/hosts/" + host, "PUT", Map.of("name", "Renamed Helfer", "address", "fixture.invalid", "user", "alek"), null).statusCode());
        definition.getAsJsonArray("bindings").get(0).getAsJsonObject().getAsJsonObject("target").addProperty("id", "local");
        assertEquals(200, call("/workflows/" + project, "PUT", definition, null).statusCode());
        assertEquals(200, call("/hosts/" + host, "DELETE", null, null).statusCode());
    }
    @Test void dockerConnectionsStayProtectedAndStaleActionsNeverReachDocker() throws Exception {
        var linked = call("/projects/" + project + "/containers", "POST", Map.of("context", "remote", "endpoint", DockerServiceTest.REMOTE, "containerId", DockerServiceTest.ID), null);
        assertEquals(201, linked.statusCode(), linked.body()); String link = JsonParser.parseString(linked.body()).getAsJsonObject().get("id").getAsString();
        assertEquals(200, call("/workflows/" + project + "/targets", "GET", null, null).statusCode());
        fake.calls.clear();
        assertEquals(409, call("/docker/environments/remote", "DELETE", Map.of("expectedEndpoint", DockerServiceTest.REMOTE), null).statusCode());
        assertEquals(409, call("/docker/environments/remote", "PUT", Map.of("expectedEndpoint", DockerServiceTest.REMOTE, "endpoint", "ssh://changed.invalid", "description", "changed"), null).statusCode());
        assertEquals(409, call("/projects/" + project + "/containers/" + link + "/stop", "POST", Map.of("environmentId", "stale-environment"), null).statusCode());
        assertTrue(fake.calls.isEmpty()); assertTrue(fake.running);
        assertEquals(200, call("/projects/" + project + "/containers/" + link, "DELETE", null, null).statusCode());
        assertTrue(fake.calls.isEmpty()); assertTrue(runtimes.workflows().get(project).resources().stream().noneMatch(r -> r.id().equals(link)));
    }
}

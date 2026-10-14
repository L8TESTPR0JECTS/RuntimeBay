package dev.runtimebay;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.Socket;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ApiServerTest {
    @TempDir Path temporary;
    RuntimeManager runtimes;
    ApiServer server;
    HttpClient client = HttpClient.newHttpClient();
    final String token = "a".repeat(64);
    @BeforeEach void setUp() throws Exception {
        Path web = Files.createDirectory(temporary.resolve("web"));
        Files.writeString(web.resolve("index.html"), "<html>Runtime Bay</html>");
        ProjectStore store = new ProjectStore(temporary.resolve("data"));
        runtimes = new RuntimeManager(store, Path.of(System.getenv().getOrDefault("RUNTIMEBAY_LAUNCHER", "../.build/runtimebay-launcher")));
        server = new ApiServer(store, runtimes, web, token);
        server.start();
    }
    @AfterEach void tearDown() { if (server != null) server.close(); if (runtimes != null) runtimes.close(); }
    HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).timeout(Duration.ofSeconds(5)); }
    int status(HttpRequest.Builder request) throws Exception { return client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode(); }
    @Test void rejectsMissingTokenAndCrossOriginCommands() throws Exception {
        assertEquals(401, status(request("/api/projects")));
        assertEquals(401, status(request("/api/projects").header("Authorization", "Bearer wrong")));
        assertEquals(403, status(request("/api/projects").header("Authorization", "Bearer " + token).header("Origin", "https://example.com")));
        assertEquals(200, status(request("/api/projects").header("Authorization", "Bearer " + token)));
        assertEquals(415, status(request("/api/projects").header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString("{}"))));
    }
    @Test void servesAssetsWithSecurityHeadersAndBlocksTraversal() throws Exception {
        var response = client.send(request("/").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
        assertEquals(404, status(request("/%2e%2e/pom.xml")));
        Files.writeString(temporary.resolve("private.txt"), "This must not be served");
        Files.createSymbolicLink(temporary.resolve("web/escape"), temporary.resolve("private.txt"));
        assertEquals(404, status(request("/escape")));
    }
    @Test void rejectsAnUnexpectedHostHeader() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(("GET /api/status HTTP/1.1\r\nHost: attacker.example\r\nAuthorization: Bearer " + token + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            String first = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            assertTrue(first.contains("403"));
        }
    }
    @Test void createsAProjectAndServiceThroughApi() throws Exception {
        Gson gson = new Gson();
        JsonObject project = new JsonObject(); project.addProperty("name", "API test"); project.addProperty("path", temporary.toString());
        var response = client.send(request("/api/projects").header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(project))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode());
        String id = JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsString();
        assertEquals(201, status(request("/api/projects/" + id + "/services").header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Worker\",\"command\":\"echo hello\",\"directory\":\".\"}"))));
        assertEquals(400, status(request("/api/projects/" + id + "/services").header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Fraction\",\"command\":\"echo hello\",\"port\":4310.5}"))));
    }
    @Test void preferencesRequireAuthenticationAndValidateSelections() throws Exception {
        assertEquals(401, status(request("/api/preferences")));
        assertEquals(200, status(request("/api/preferences").header("Authorization", "Bearer " + token)));
        var valid = "{\"version\":1,\"palette\":\"violet\",\"appearance\":\"light\",\"onboardingComplete\":true}";
        assertEquals(200, status(request("/api/preferences").header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(valid))));
        assertEquals(400, status(request("/api/preferences").header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(valid.replace("violet", "unsupported")))));
        var response = client.send(request("/api/preferences").header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.body().contains("violet"));
    }

}

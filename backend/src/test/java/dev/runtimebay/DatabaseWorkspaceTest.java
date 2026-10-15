package dev.runtimebay;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseWorkspaceTest {
    @TempDir Path temporary;
    ProjectStore store;
    DatabaseWorkspace workspace;
    HttpServer upstream, gateway;
    HttpClient client = HttpClient.newHttpClient();
    String project, origin;
    AtomicInteger launches = new AtomicInteger(), stops = new AtomicInteger(), forwarded = new AtomicInteger();
    volatile String seenCookie, seenAuthorization, seenOrigin;
    @BeforeEach void setup() throws Exception {
        store = new ProjectStore(temporary.resolve("data")); project = store.addProject("Fixture", temporary.toString()).id();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath(); String text;
            if (path.endsWith("api/gql")) {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                text = request.contains("authLogin") ? "{\"data\":{\"authLogin\":{\"authStatus\":\"SUCCESS\"}}}" : "{\"data\":{\"openSession\":{\"valid\":true}}}";
                exchange.getResponseHeaders().add("Set-Cookie", "cb-session-id=upstream-private; Path=/; HttpOnly");
            } else {
                forwarded.incrementAndGet(); seenCookie = exchange.getRequestHeaders().getFirst("Cookie"); seenAuthorization = exchange.getRequestHeaders().getFirst("Authorization"); seenOrigin = exchange.getRequestHeaders().getFirst("Origin");
                text = "<html>Database fixture</html>"; exchange.getResponseHeaders().set("Content-Type", "text/html");
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); upstream.start();
        gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); origin = "http://127.0.0.1:" + gateway.getAddress().getPort();
        workspace = new DatabaseWorkspace(store, (selected, directory, prefix, theme, progress) -> {
            launches.incrementAndGet(); return new DatabaseWorkspace.Running(URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), stops::incrementAndGet);
        }, origin);
        gateway.createContext("/", exchange -> {
            try {
                if (exchange.getRequestURI().getPath().equals("/grant")) { workspace.authorizeFrame(exchange, project); exchange.sendResponseHeaders(204, -1); }
                else workspace.proxy(exchange);
            } catch (ApiException error) { exchange.sendResponseHeaders(error.status, -1); }
            catch (Exception error) { exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        }); gateway.start();
    }
    @AfterEach void close() { if (workspace != null) workspace.close(); if (gateway != null) gateway.stop(0); if (upstream != null) upstream.stop(0); }
    void ready() {
        workspace.start(project, "light");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> { while (workspace.status(project).state().equals("starting")) Thread.sleep(10); });
        assertEquals("ready", workspace.status(project).state());
    }
    String cookie() throws Exception { return client.send(HttpRequest.newBuilder(URI.create(origin + "/grant")).GET().build(), HttpResponse.BodyHandlers.discarding()).headers().firstValue("Set-Cookie").orElseThrow(); }
    HttpResponse<String> get(String path, String cookie, String from) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(origin + path));
        if (cookie != null) builder.header("Cookie", cookie.split(";",2)[0]); if (from != null) builder.header("Origin", from);
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void startsOnlyOnRequestAndReusesEachWorkflowCompanion() throws Exception {
        assertEquals("closed", workspace.status(project).state()); assertEquals(0, launches.get());
        ready(); workspace.start(project, "dark"); assertEquals(1, launches.get());
        assertEquals("/database/" + project + "/", workspace.status(project).framePath());
        assertFalse(workspace.status(project).toString().contains("PASSWORD"));
    }
    @Test void privateGateRejectsMissingCookiesAndCrossOriginAccess() throws Exception {
        ready(); String path = workspace.status(project).framePath(), cookie = cookie();
        assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("SameSite=Strict")); assertTrue(cookie.contains("Path=" + path));
        assertEquals(401, get(path, null, null).statusCode());
        assertEquals(403, get(path, cookie, "https://example.com").statusCode());
        assertEquals(200, get(path, cookie, origin).statusCode());
    }
    @Test void browserNeverSuppliesTheUpstreamLoginOrReceivesItsSessionCookie() throws Exception {
        ready(); String cookie = cookie(), path = workspace.status(project).framePath();
        var response = client.send(HttpRequest.newBuilder(URI.create(origin + path)).header("Cookie", cookie.split(";",2)[0]).header("Authorization", "Bearer must-not-leak").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode()); assertTrue(response.body().contains("Database fixture"));
        assertTrue(seenCookie.contains("cb-session-id=upstream-private")); assertFalse(seenCookie.contains("runtimebay-db-")); assertNull(seenAuthorization);
        assertEquals("http://127.0.0.1:" + upstream.getAddress().getPort(), seenOrigin);
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
        assertTrue(response.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'self'"));
    }
    @Test void rejectsTraversalUnknownProjectsAndInvalidAppearance() throws Exception {
        assertThrows(ApiException.class, () -> workspace.start("unknown", "light"));
        assertThrows(ApiException.class, () -> workspace.start(project, "remote-url"));
        ready(); String cookie = cookie(), path = workspace.status(project).framePath();
        assertEquals(400, get(path + "%2e%2e/api/projects", cookie, null).statusCode());
        assertEquals(400, get(path + "assets%2f..%2fprivate", cookie, null).statusCode());
        assertEquals(404, get("/database/unknown/", cookie, null).statusCode());
    }
    @Test void stoppingKeepsSavedConfigurationAndRevokesFrameAccess() throws Exception {
        ready(); String cookie = cookie(), path = workspace.status(project).framePath();
        workspace.stop(project); assertEquals(1, stops.get()); assertEquals("closed", workspace.status(project).state());
        assertEquals(503, get(path, cookie, null).statusCode());
        assertTrue(Files.exists(store.directory().resolve("databases").resolve(project).resolve(".cloudbeaver.auto.conf")));
    }
    @Test void closingDuringStartupCannotReviveTheCompanion() throws Exception {
        workspace.close();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        workspace = new DatabaseWorkspace(store, (selected, directory, prefix, theme, progress) -> {
            started.countDown(); release.await(); return new DatabaseWorkspace.Running(URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), stops::incrementAndGet);
        }, origin);
        workspace.start(project, "light"); assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
        workspace.stop(project); release.countDown();
        assertEquals("closed", workspace.status(project).state()); assertNull(workspace.status(project).framePath());
    }
    @Test void oneWorkflowCookieCannotOpenAnotherWorkflowFrame() throws Exception {
        ready(); String first = cookie();
        String other = store.addProject("Other", Files.createDirectory(temporary.resolve("other")).toString()).id();
        workspace.start(other, "light");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> { while (workspace.status(other).state().equals("starting")) Thread.sleep(10); });
        assertEquals("ready", workspace.status(other).state());
        assertEquals(401, get(workspace.status(other).framePath(), first, null).statusCode());
    }
    @Test void appearanceRefreshPreservesExistingCloudBeaverSettings() throws Exception {
        Path directory = temporary.resolve("theme"); DatabaseWorkspace.prepare(directory, "Fixture", "/database/fixture/", "light");
        Path runtime = directory.resolve("workspace/.data/.cloudbeaver.runtime.conf"); Files.createDirectories(runtime.getParent());
        Files.writeString(runtime, "{\"server\":{\"productSettings\":{\"core.theming.theme\":\"light\",\"another.setting\":true}},\"app\":{\"anonymousAccessEnabled\":false}}");
        DatabaseWorkspace.prepare(directory, "Fixture", "/database/fixture/", "dark");
        var saved = com.google.gson.JsonParser.parseString(Files.readString(runtime)).getAsJsonObject();
        assertEquals("dark", saved.getAsJsonObject("server").getAsJsonObject("productSettings").get("core.theming.theme").getAsString());
        assertTrue(saved.getAsJsonObject("server").getAsJsonObject("productSettings").get("another.setting").getAsBoolean());
        assertFalse(saved.getAsJsonObject("app").get("anonymousAccessEnabled").getAsBoolean());
    }
    @Test void privateAccountIsPreservedAndWorkspaceConfigurationIsConfined() throws Exception {
        Path directory = temporary.resolve("private"); DatabaseWorkspace.prepare(directory, "Gobble", "/database/fixture/", "dark");
        Path file = directory.resolve(".cloudbeaver.auto.conf"); String first = Files.readString(file, StandardCharsets.ISO_8859_1);
        DatabaseWorkspace.prepare(directory, "Renamed", "/database/fixture/", "light"); assertEquals(first, Files.readString(file, StandardCharsets.ISO_8859_1));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        String config = Files.readString(directory.resolve("cloudbeaver.conf"));
        assertTrue(config.contains("\"anonymousAccessEnabled\":false")); assertTrue(config.contains("\"forwardProxy\":false"));
        Properties properties = new Properties(); try (Reader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) { properties.load(reader); }
        assertFalse(config.contains(properties.getProperty("CB_ADMIN_PASSWORD")));
    }
}

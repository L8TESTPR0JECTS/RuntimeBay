package dev.runtimebay;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

final class ApiServer implements AutoCloseable {
    private final HttpServer server;
    private final ProjectStore store;
    private final RuntimeManager runtimes;
    private final DockerService docker;
    private final AppearanceStore appearance;
    private final TerminalManager terminals;
    private final Path webRoot;
    private final String token;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    ApiServer(ProjectStore store, RuntimeManager runtimes, Path webRoot, String token) throws IOException {
        this(store, runtimes, webRoot, token, new DockerService(null));
    }
    ApiServer(ProjectStore store, RuntimeManager runtimes, Path webRoot, String token, DockerService docker) throws IOException {
        if (token == null || token.length() < 32) throw new IllegalArgumentException("A strong session token is required");
        this.store = store; this.runtimes = runtimes; this.webRoot = webRoot.toRealPath(); this.token = token; this.docker = docker;
        appearance = new AppearanceStore(store.directory());
        Path supervisor = Path.of(System.getenv().getOrDefault("RUNTIMEBAY_LAUNCHER", "../.build/runtimebay-launcher"));
        terminals = new TerminalManager(store, supervisor.resolveSibling("runtimebay-terminal"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 32);
        server.setExecutor(requests);
        server.createContext("/", this::handle);
    }
    void start() { server.start(); }
    int port() { return server.getAddress().getPort(); }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            headers(exchange);
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!("127.0.0.1:" + port()).equals(host)) throw new ApiException(403, "Invalid host");
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8), auth.getBytes(StandardCharsets.UTF_8)))
                    throw new ApiException(401, "Open this workspace from Runtime Bay to connect");
                String origin = exchange.getRequestHeaders().getFirst("Origin");
                if (origin != null && !("http://127.0.0.1:" + port()).equals(origin)) throw new ApiException(403, "Invalid origin");
                route(exchange, path);
            } else assets(exchange, path);
        } catch (ApiException e) { json(exchange, e.status, Map.of("error", e.getMessage())); }
        catch (JsonParseException | IllegalArgumentException e) { json(exchange, 400, Map.of("error", "Invalid request: " + e.getMessage())); }
        catch (Exception e) {
            System.err.println("Request failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            json(exchange, 500, Map.of("error", "Operation failed. Check that the project is accessible and try again."));
        } finally { exchange.close(); }
    }
    private void route(HttpExchange e, String path) throws IOException {
        String method = e.getRequestMethod();
        String[] parts = path.substring(1).split("/");
        if (path.equals("/api/terminals") || path.startsWith("/api/terminals/")) { routeTerminals(e, parts); return; }
        if (path.startsWith("/api/docker/")) { routeDocker(e, parts); return; }
        if (path.equals("/api/preferences")) {
            if (method.equals("GET")) { json(e, 200, appearance.get()); return; }
            if (method.equals("PUT")) { json(e, 200, appearance.save(body(e))); return; }
        }
        if (path.equals("/api/status") && method.equals("GET")) {
            json(e, 200, Map.of("name", "Runtime Bay", "version", "0.4.0", "platform", "macOS")); return;
        }
        if (path.equals("/api/projects")) {
            if (method.equals("GET")) { json(e, 200, runtimes.projects()); return; }
            if (method.equals("POST")) {
                JsonObject body = body(e);
                json(e, 201, store.addProject(string(body, "name"), string(body, "path"))); return;
            }
        }
        if (parts.length >= 4 && parts[1].equals("projects") && parts[3].equals("services")) {
            String project = parts[2];
            if ((parts.length == 4 && method.equals("POST")) || (parts.length == 5 && method.equals("PUT"))) {
                JsonObject body = body(e);
                Integer port = null;
                if (body.has("port") && !body.get("port").isJsonNull()) {
                    JsonElement value = body.get("port");
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !value.getAsString().matches("[0-9]{1,5}"))
                        throw new ApiException(400, "Port must be a whole number between 1 and 65535");
                    port = value.getAsInt();
                }
                json(e, parts.length == 4 ? 201 : 200, runtimes.save(project, parts.length == 5 ? parts[4] : null,
                    string(body, "name"), string(body, "command"), string(body, "directory"), port)); return;
            }
            if (parts.length == 5 && method.equals("DELETE")) {
                runtimes.remove(project, parts[4]); json(e, 200, Map.of("ok", true)); return;
            }
            if (parts.length == 6) {
                String id = parts[4];
                if (method.equals("GET") && parts[5].equals("logs")) {
                    long after = 0;
                    String query = e.getRequestURI().getRawQuery();
                    if (query != null && query.matches("after=[0-9]+")) after = Long.parseLong(query.substring(6));
                    json(e, 200, runtimes.logs(project, id, after)); return;
                }
                if (method.equals("POST")) {
                    body(e);
                    switch (parts[5]) {
                        case "start" -> runtimes.start(project, id);
                        case "stop" -> runtimes.stop(project, id);
                        case "restart" -> runtimes.restart(project, id);
                        default -> throw new ApiException(404, "Action not found");
                    }
                    json(e, 200, Map.of("ok", true)); return;
                }
            }
        }
        throw new ApiException(404, "Endpoint not found");
    }
    private void routeTerminals(HttpExchange e, String[] parts) throws IOException {
        String method = e.getRequestMethod();
        if (parts.length == 2) {
            if (method.equals("GET")) { json(e, 200, terminals.list()); return; }
            if (method.equals("POST")) { json(e, 201, terminals.create(string(body(e), "projectId"))); return; }
        }
        if (parts.length == 3 && method.equals("DELETE")) { terminals.remove(parts[2]); json(e, 200, Map.of("ok", true)); return; }
        if (parts.length == 4) {
            String id = parts[2];
            if (parts[3].equals("output") && method.equals("GET")) {
                String cursor = query(e, "after");
                if (cursor == null || !cursor.matches("[0-9]{1,18}")) throw new ApiException(400, "An output cursor is required");
                try { json(e, 200, terminals.output(id, Long.parseLong(cursor))); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new ApiException(503, "Terminal connection closed"); }
                return;
            }
            if (parts[3].equals("input") && method.equals("POST")) { terminals.input(id, string(body(e), "data")); json(e, 200, Map.of("ok", true)); return; }
            if (parts[3].equals("resize") && method.equals("POST")) {
                JsonObject value = body(e);
                for (String key : List.of("columns", "rows")) {
                    if (!value.has(key) || !value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isNumber() || !value.get(key).getAsString().matches("[0-9]{1,4}")) throw new ApiException(400, "Invalid terminal dimensions");
                }
                terminals.resize(id, value.get("columns").getAsInt(), value.get("rows").getAsInt()); json(e, 200, Map.of("ok", true)); return;
            }
        }
        throw new ApiException(404, "Terminal endpoint not found");
    }
    private void routeDocker(HttpExchange e, String[] parts) throws IOException {
        String method = e.getRequestMethod();
        if (parts.length == 3 && parts[2].equals("operations") && method.equals("GET")) { json(e, 200, docker.operations()); return; }
        if (parts.length == 3 && parts[2].equals("environments")) {
            if (method.equals("GET")) { json(e, 200, docker.environments()); return; }
            if (method.equals("POST")) {
                JsonObject value = body(e);
                json(e, 201, docker.addSshEnvironment(string(value, "name"), string(value, "endpoint"))); return;
            }
        }
        if (parts.length >= 4 && parts[2].equals("operations")) {
            if (parts.length == 4 && method.equals("GET")) { json(e, 200, docker.operation(parts[3])); return; }
            if (parts.length == 5 && parts[4].equals("cancel") && method.equals("POST")) {
                body(e); json(e, 200, docker.cancel(parts[3])); return;
            }
        }
        if (parts.length >= 5 && parts[2].equals("environments")) {
            String context = parts[3];
            if (parts.length == 5 && method.equals("GET")) {
                String endpoint = query(e, "endpoint");
                if (parts[4].equals("overview")) { json(e, 200, docker.overview(context, endpoint)); return; }
                if (parts[4].equals("inventory")) { json(e, 200, docker.inventory(context, endpoint)); return; }
            }
            if (parts.length == 5 && parts[4].equals("containers") && method.equals("POST")) {
                JsonObject value = body(e);
                if (!value.has("definition") || !value.get("definition").isJsonObject()) throw new ApiException(400, "A container definition is required");
                JsonObject definition = value.getAsJsonObject("definition");
                if (definition.has("ports") && definition.get("ports").isJsonArray()) for (JsonElement port : definition.getAsJsonArray("ports")) {
                    if (!port.isJsonObject()) throw new ApiException(400, "Invalid published port");
                    for (String key : List.of("host", "container")) {
                        JsonElement number = port.getAsJsonObject().get(key);
                        if (number == null || !number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber() || !number.getAsString().matches("[0-9]{1,5}")) throw new ApiException(400, "Published ports must be whole numbers between 1 and 65535");
                    }
                }
                DockerModels.Create create = gson.fromJson(definition, DockerModels.Create.class);
                if (create.projectId() != null && !create.projectId().isBlank()) store.project(create.projectId());
                json(e, 201, Map.of("id", docker.create(context, string(value, "endpoint"), create))); return;
            }
            if (parts.length == 6 && parts[4].equals("images") && parts[5].equals("pull") && method.equals("POST")) {
                JsonObject value = body(e); json(e, 202, docker.pull(context, string(value, "endpoint"), string(value, "image"))); return;
            }
            if (parts.length == 6 && parts[4].equals("containers") && method.equals("GET")) {
                json(e, 200, docker.details(context, query(e, "endpoint"), parts[5])); return;
            }
            if (parts.length == 7 && parts[4].equals("containers") && method.equals("POST")) {
                JsonObject value = body(e);
                docker.action(context, string(value, "endpoint"), parts[5], parts[6]); json(e, 200, Map.of("ok", true)); return;
            }
        }
        throw new ApiException(404, "Container endpoint not found");
    }
    private String query(HttpExchange e, String name) {
        String query = e.getRequestURI().getRawQuery();
        if (query != null) for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(name)) return pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
        }
        return null;
    }
    private JsonObject body(HttpExchange e) throws IOException {
        String type = e.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.split(";")[0].trim().equalsIgnoreCase("application/json"))
            throw new ApiException(415, "Use application/json");
        byte[] data = e.getRequestBody().readNBytes(65537);
        if (data.length > 65536) throw new ApiException(413, "Request too large");
        JsonElement value = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
        if (!value.isJsonObject()) throw new ApiException(400, "Expected a JSON object");
        return value.getAsJsonObject();
    }
    private String string(JsonObject body, String key) {
        if (!body.has(key) || body.get(key).isJsonNull()) return null;
        if (!body.get(key).isJsonPrimitive() || !body.get(key).getAsJsonPrimitive().isString())
            throw new ApiException(400, key + " must be text");
        return body.get(key).getAsString();
    }
    private void assets(HttpExchange e, String path) throws IOException {
        if (!e.getRequestMethod().equals("GET")) throw new ApiException(405, "Method not allowed");
        Path file = webRoot.resolve(path.equals("/") ? "index.html" : path.substring(1)).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(webRoot))
            throw new ApiException(404, "File not found");
        String name = file.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html" : name.endsWith(".js") ? "text/javascript" : name.endsWith(".css") ? "text/css" : name.endsWith(".png") ? "image/png" : "application/octet-stream";
        e.getResponseHeaders().set("Content-Type", type + (type.startsWith("text/") ? "; charset=utf-8" : ""));
        e.sendResponseHeaders(200, Files.size(file));
        Files.copy(file, e.getResponseBody());
    }
    private void headers(HttpExchange e) {
        e.getResponseHeaders().set("Cache-Control", "no-store");
        e.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        e.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        e.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
    }
    private void json(HttpExchange e, int status, Object value) throws IOException {
        byte[] bytes = gson.toJson(value).getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        e.sendResponseHeaders(status, bytes.length);
        e.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(0); terminals.close(); requests.shutdownNow(); }
}

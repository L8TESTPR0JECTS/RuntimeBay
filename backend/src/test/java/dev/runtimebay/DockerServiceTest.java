package dev.runtimebay;

import com.google.gson.*;
import dev.runtimebay.DockerModels.*;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

class DockerServiceTest {
    static final String ID = "c".repeat(64);
    static final String LOCAL = "unix:///fixture/docker.sock", REMOTE = "ssh://developer@remote.example.com";
    FakeDocker fake;
    DockerService docker;
    @BeforeEach void setup() { fake = new FakeDocker(); docker = new DockerService(fake); }
    @AfterEach void cleanup() { docker.close(); }

    static class FakeDocker implements DockerService.Commands {
        final Map<String, JsonObject> contexts = new ConcurrentHashMap<>();
        final List<List<String>> calls = Collections.synchronizedList(new ArrayList<>());
        boolean running = true, paused;
        volatile CountDownLatch pulling, release;
        volatile Path envFile;
        volatile String envContents;
        volatile boolean failPull;
        FakeDocker() {
            contexts.put("local", context("local", LOCAL, false, false));
            contexts.put("remote", context("remote", REMOTE, false, false));
            contexts.put("tls", context("tls", "tcp://tls.example.com:2376", false, true));
            contexts.put("unsafe", context("unsafe", "tcp://unsafe.example.com:2375", false, false));
            contexts.put("skipverify", context("skipverify", "tcp://unsafe.example.com:2376", true, true));
        }
        static JsonObject context(String name, String host, boolean skipVerify, boolean tls) {
            return JsonParser.parseString("{\"Name\":\"" + name + "\",\"Metadata\":{\"Description\":\"Fixture\"},\"Endpoints\":{\"docker\":{\"Host\":\"" + host + "\",\"SkipTLSVerify\":" + skipVerify + "}},\"TLSMaterial\":" + (tls ? "{\"docker\":[\"ca.pem\",\"cert.pem\",\"key.pem\"]}" : "{}") + "}").getAsJsonObject();
        }
        static DockerService.Result ok(String output) { return new DockerService.Result(0, output, ""); }
        public DockerService.Result run(List<String> args, Duration timeout, Consumer<String> progress) throws IOException, InterruptedException {
            calls.add(List.copyOf(args));
            if (args.size() >= 2 && args.get(0).equals("context")) {
                switch (args.get(1)) {
                    case "ls":
                        return ok(contexts.keySet().stream().sorted().map(name -> "{\"Name\":\"" + name + "\",\"Current\":" + name.equals("local") + "}").reduce("", (a, b) -> a + b + "\n"));
                    case "inspect":
                        JsonArray results = new JsonArray();
                        for (String name : args.subList(2, args.size())) {
                            JsonObject context = contexts.get(name);
                            if (context == null) return new DockerService.Result(1, "", "context not found");
                            results.add(context);
                        }
                        return ok(results.toString());
                    case "create":
                        contexts.put(args.get(2), context(args.get(2), args.getLast().substring("host=".length()), false, false));
                        return ok(args.get(2));
                }
            }
            assertEquals("--context", args.get(0));
            List<String> command = args.subList(2, args.size());
            switch (command.get(0)) {
                case "info": return ok("{\"Name\":\"fixture-engine\",\"ServerVersion\":\"29.0\",\"NCPU\":4,\"MemTotal\":1073741824,\"Containers\":1,\"ContainersRunning\":1,\"Images\":2,\"OperatingSystem\":\"Linux\",\"Architecture\":\"aarch64\"}");
                case "container":
                    switch (command.get(1)) {
                        case "ls": return ok("{\"ID\":\"" + ID + "\",\"Names\":\"api\",\"Image\":\"nginx:alpine\",\"State\":\"running\",\"Status\":\"Up 1 minute\",\"Ports\":\"127.0.0.1:8080->80/tcp\",\"Labels\":\"com.docker.compose.project=demo,dev.runtimebay.project=workspace-id\"}\n");
                        case "inspect": return ok("{\"Name\":\"/api\",\"State\":{\"Running\":" + running + ",\"Paused\":" + paused + ",\"Status\":\"running\",\"ExitCode\":0},\"Config\":{\"Image\":\"nginx:alpine\",\"Env\":[\"PASSWORD=private-value\"]},\"HostConfig\":{\"RestartPolicy\":{\"Name\":\"unless-stopped\"}},\"Mounts\":[{\"Type\":\"volume\",\"Name\":\"data\",\"Destination\":\"/data\",\"RW\":true}],\"NetworkSettings\":{\"Networks\":{\"bridge\":{}}}}");
                        case "logs": return new DockerService.Result(0, "fixture stdout\n", "fixture stderr\n");
                        case "stats": return ok("{\"CPUPerc\":\"1.25%\",\"MemUsage\":\"8MiB / 1GiB\",\"MemPerc\":\"0.78%\",\"NetIO\":\"1kB / 2kB\",\"BlockIO\":\"0B / 0B\",\"PIDs\":\"2\"}\n");
                        case "create":
                            if (command.contains("--env-file")) { envFile = Path.of(command.get(command.indexOf("--env-file") + 1)); envContents = Files.readString(envFile); assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(envFile))); }
                            return ok(ID);
                        case "stop": running = false; return ok(ID);
                        case "start", "restart": running = true; return ok(ID);
                        case "pause": paused = true; return ok(ID);
                        case "unpause": paused = false; return ok(ID);
                        case "rm": return ok(ID);
                    }
                    break;
                case "image":
                    if (command.get(1).equals("ls")) return ok("{\"ID\":\"sha256:abc\",\"Repository\":\"nginx\",\"Tag\":\"alpine\",\"Size\":\"12MB\",\"CreatedSince\":\"1 week ago\"}\n");
                    if (command.get(1).equals("pull")) {
                        if (progress != null) progress.accept("Pulling fixture layer\n");
                        if (pulling != null) pulling.countDown();
                        if (release != null) release.await();
                        return failPull ? new DockerService.Result(1, "", "registry unavailable") : ok("Pulled");
                    }
                    break;
                case "volume": return ok("{\"Name\":\"data\",\"Driver\":\"local\",\"Scope\":\"local\"}\n");
                case "network": return ok("{\"ID\":\"n1\",\"Name\":\"bridge\",\"Driver\":\"bridge\",\"Scope\":\"local\"}\n");
            }
            throw new AssertionError("Unexpected Docker command " + args);
        }
    }
    void await(BooleanSupplier done) { assertTimeoutPreemptively(Duration.ofSeconds(5), () -> { while (!done.getAsBoolean()) Thread.sleep(10); }); }
    Create definition(Map<String, String> environment) { return new Create("api", "nginx:alpine", List.of("--privileged", "literal; $(touch /tmp/unwanted)"), List.of(new PublishedPort(8080, 80)), List.of(new Mount("data", "/data", true)), "unless-stopped", "workspace-id", environment); }

    @Test void discoversLocalSshAndVerifiedTlsAndRejectsInsecureTcp() {
        Discovery result = docker.environments();
        assertTrue(result.available());
        assertEquals(5, result.environments().size());
        assertTrue(result.environments().stream().filter(e -> Set.of("local", "remote", "tls").contains(e.name())).allMatch(Environment::supported));
        assertTrue(result.environments().stream().filter(e -> Set.of("unsafe", "skipverify").contains(e.name())).noneMatch(Environment::supported));
        assertEquals("ssh", docker.resolve("remote", REMOTE).transport());
    }
    @Test void allRemoteOperationsUseTheChosenContextAndNoGlobalContextSwitch() {
        Overview result = docker.overview("remote", REMOTE);
        assertEquals("remote", result.environment().name());
        assertEquals("demo", result.containers().getFirst().composeProject());
        assertEquals("workspace-id", result.containers().getFirst().workspaceProject());
        docker.action("remote", REMOTE, ID, "restart");
        assertTrue(fake.calls.stream().filter(a -> a.get(0).equals("--context")).allMatch(a -> a.get(1).equals("remote")));
        assertTrue(fake.calls.stream().noneMatch(a -> a.contains("use")));
    }
    @Test void rejectsChangedEndpointsBeforeSendingEngineCommands() {
        assertEquals(409, assertThrows(ApiException.class, () -> docker.overview("remote", "ssh://different.example.com")).status);
        assertTrue(fake.calls.stream().noneMatch(a -> a.contains("--context")));
        assertThrows(ApiException.class, () -> docker.action("unsafe", "tcp://unsafe.example.com:2375", ID, "start"));
    }
    @Test void validatesTargetsAndInputsAgainstFlagOrShellInjection() {
        for (String value : List.of("-H", "name; rm -rf /", "name with spaces")) assertFalse(DockerService.validContext(value));
        for (String value : List.of("-x", "$(touch /tmp/unwanted)", "nginx:alpine --privileged", "https://image with space")) assertThrows(ApiException.class, () -> DockerService.imageReference(value));
        for (String value : List.of("ssh://user:password@remote.example.com", "tcp://remote.example.com", "ssh://-oProxyCommand=x", "ssh://remote.example.com?option=x")) assertThrows(ApiException.class, () -> DockerService.validateSsh(value));
        assertDoesNotThrow(() -> DockerService.validateSsh("ssh://user@[::1]:2222/run/user/1000/docker.sock"));
        assertThrows(ApiException.class, () -> docker.action("local", LOCAL, "--all", "stop"));
        assertThrows(ApiException.class, () -> docker.action("local", LOCAL, ID, "prune"));
    }
    @Test void createUsesLiteralArgumentsLoopbackPortsNamedVolumesAndPrivateEnvironmentFile() {
        assertEquals(ID, docker.create("remote", REMOTE, definition(Map.of("PASSWORD", "private-value"))));
        List<String> args = fake.calls.getLast();
        assertTrue(args.contains("127.0.0.1:8080:80/tcp"));
        assertTrue(args.contains("type=volume,source=data,target=/data,readonly"));
        assertTrue(args.contains("dev.runtimebay.project=workspace-id"));
        assertEquals("nginx:alpine", args.get(args.indexOf("--") + 1));
        assertTrue(args.indexOf("--privileged") > args.indexOf("nginx:alpine"));
        assertFalse(args.toString().contains("private-value"));
        assertEquals("PASSWORD=private-value\n", fake.envContents);
        assertFalse(Files.exists(fake.envFile));
    }
    @Test void rejectsBadDefinitionsAndEnvironmentLineInjection() {
        assertThrows(ApiException.class, () -> DockerService.createArguments(definition(Map.of("PASSWORD", "value\nINJECTED=bad"))));
        assertThrows(ApiException.class, () -> DockerService.createArguments(new Create("api", "nginx", List.of(), List.of(new PublishedPort(70000, 80)), List.of(), "no", null, Map.of())));
        assertThrows(ApiException.class, () -> DockerService.createArguments(new Create("api", "nginx", List.of(), List.of(), List.of(new Mount("/etc", "/data", false)), "no", null, Map.of())));
    }
    @Test void refusesRemovingRunningContainerAndDoesNotRemoveVolumesWhenStopped() {
        assertEquals(409, assertThrows(ApiException.class, () -> docker.action("local", LOCAL, ID, "remove")).status);
        docker.action("local", LOCAL, ID, "stop");
        docker.action("local", LOCAL, ID, "remove");
        assertEquals(List.of("--context", "local", "container", "rm", "--", ID), fake.calls.getLast());
        assertTrue(fake.calls.stream().noneMatch(a -> a.contains("--force") || a.contains("--volumes") || a.contains("prune")));
    }
    @Test void detailsAndInventoryExposeUsefulFieldsWithoutContainerEnvironmentSecrets() {
        Details detail = docker.details("local", LOCAL, ID);
        assertTrue(detail.logs().contains("fixture stdout")); assertTrue(detail.logs().contains("fixture stderr"));
        assertEquals("1.25%", detail.metrics().cpu()); assertEquals(List.of("bridge"), detail.networks());
        assertFalse(new Gson().toJson(detail).contains("private-value"));
        Inventory resources = docker.inventory("remote", REMOTE);
        assertEquals("nginx", resources.images().getFirst().repository());
        assertEquals("data", resources.volumes().getFirst().name());
        assertEquals("bridge", resources.networks().getFirst().name());
    }
    @Test void addsSshContextWithoutSwitchingGlobalContext() {
        Environment created = docker.addSshEnvironment("new-host", "ssh://user@new.example.com:2222");
        assertEquals("ssh", created.transport());
        assertTrue(fake.calls.stream().anyMatch(a -> a.equals(List.of("context", "create", "new-host", "--description", "Runtime Bay SSH environment", "--docker", "host=ssh://user@new.example.com:2222"))));
        assertTrue(fake.calls.stream().noneMatch(a -> a.contains("use")));
    }
    @Test void tracksPullProgressAndCancelsAnInFlightPull() throws Exception {
        fake.pulling = new CountDownLatch(1); fake.release = new CountDownLatch(1);
        Operation operation = docker.pull("remote", REMOTE, "nginx:alpine");
        assertTrue(fake.pulling.await(3, TimeUnit.SECONDS));
        assertEquals(operation.id(), docker.pull("remote", REMOTE, "nginx:alpine").id());
        assertTrue(docker.operation(operation.id()).output().contains("Pulling fixture layer"));
        assertEquals(operation.id(), docker.operations().getFirst().id());
        docker.cancel(operation.id());
        await(() -> docker.operation(operation.id()).state().equals("cancelled"));
        assertTrue(fake.calls.stream().anyMatch(a -> a.equals(List.of("--context", "remote", "image", "pull", "--", "nginx:alpine"))));
    }
    @Test void reportsPullFailuresAndMakesMissingDockerOptional() {
        fake.failPull = true;
        Operation operation = docker.pull("local", LOCAL, "missing:tag");
        await(() -> docker.operation(operation.id()).state().equals("failed"));
        assertEquals("registry unavailable", docker.operation(operation.id()).error());
        try (DockerService absent = new DockerService(null)) {
            assertFalse(absent.environments().available());
            assertEquals(503, assertThrows(ApiException.class, () -> absent.overview("local", LOCAL)).status);
        }
    }
    @Test void stripsRuntimeBaySecretsAndAmbientHostOverridesButPreservesDockerAndSshCredentials() {
        Map<String, String> environment = new HashMap<>(Map.of("RUNTIMEBAY_TOKEN", "private-token", "DOCKER_HOST", "tcp://wrong-host:2375", "DOCKER_CONTEXT", "wrong", "DOCKER_TLS_VERIFY", "0", "DOCKER_CERT_PATH", "/wrong", "DOCKER_CONFIG", "/user/docker", "SSH_AUTH_SOCK", "/ssh-agent"));
        DockerCommandRunner.cleanEnvironment(environment);
        assertEquals(Map.of("DOCKER_CONFIG", "/user/docker", "SSH_AUTH_SOCK", "/ssh-agent"), environment);
    }
}

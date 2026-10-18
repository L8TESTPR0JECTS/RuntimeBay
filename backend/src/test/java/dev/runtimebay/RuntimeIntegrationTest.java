package dev.runtimebay;

import dev.runtimebay.Models.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.MethodName.class)
class RuntimeIntegrationTest {
    @TempDir Path temporary;
    ProjectStore store;
    RuntimeManager runtimes;
    Project project;
    Path launcher;
    @BeforeEach void setUp() throws Exception {
        launcher = Path.of(System.getenv().getOrDefault("RUNTIMEBAY_LAUNCHER", "../.build/runtimebay-launcher")).toAbsolutePath();
        assertTrue(Files.isExecutable(launcher), "Compile the macOS launcher before running integration tests");
        store = new ProjectStore(temporary.resolve("data"));
        project = store.addProject("Test workspace", temporary.toString());
        runtimes = new RuntimeManager(store, launcher);
    }
    @AfterEach void tearDown() { if (runtimes != null) runtimes.close(); }
    Service add(String command) throws IOException { return store.saveService(project.id(), null, "Service", command, ".", null); }
    ServiceView view(Service service) { return runtimes.projects().getFirst().services().stream().filter(s -> s.id().equals(service.id())).findFirst().orElseThrow(); }
    void await(BooleanSupplier predicate) {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> { while (!predicate.getAsBoolean()) Thread.sleep(20); });
    }
    @Test void capturesBothStreamsAndExitCode() throws Exception {
        Service service = add("printf 'hello\\n'; printf 'problem\\n' >&2; exit 7");
        runtimes.start(project.id(), service.id());
        await(() -> view(service).state().equals("failed"));
        assertEquals(7, view(service).exitCode());
        var logs = runtimes.logs(project.id(), service.id(), 0).entries();
        assertTrue(logs.stream().anyMatch(l -> l.stream().equals("stdout") && l.message().equals("hello")));
        assertTrue(logs.stream().anyMatch(l -> l.stream().equals("stderr") && l.message().equals("problem")));
        assertNull(view(service).pid());
    }
    @Test void workflowTargetCannotBeChangedDuringExecutionAndRemoteTargetNeverFallsBackLocally() throws Exception {
        Service service = add("sleep 120");
        runtimes.start(project.id(), service.id());
        var workflow = runtimes.workflows().get(project.id());
        var remote = new WorkflowModels.Workflow(workflow.id(), workflow.projectId(), workflow.name(), workflow.defaultEnvironmentId(), workflow.environments(),
            java.util.List.of(new WorkflowModels.MachineReference("local"), new WorkflowModels.MachineReference("missing-host")), workflow.dockerConnections(), workflow.resources(),
            workflow.bindings().stream().map(b -> new WorkflowModels.Binding(b.id(), b.environmentId(), b.resourceId(), new WorkflowModels.Target("machine", "missing-host"))).toList());
        assertEquals(409, assertThrows(ApiException.class, () -> runtimes.saveWorkflow(project.id(), remote)).status);
        assertEquals(workflow, runtimes.workflows().get(project.id()));
        runtimes.stop(project.id(), service.id());
        runtimes.saveWorkflow(project.id(), remote);
        assertEquals(409, assertThrows(ApiException.class, () -> runtimes.start(project.id(), service.id())).status);
        assertNull(view(service).pid());
    }
    @Test void stopsGrandchildrenAndRestartsInNewSession() throws Exception {
        Path pidFile = temporary.resolve("child.pid");
        Service service = add("sleep 120 & echo $! > '" + pidFile + "'; wait");
        runtimes.start(project.id(), service.id());
        await(() -> Files.exists(pidFile));
        long child = Long.parseLong(Files.readString(pidFile).trim());
        long firstSession = view(service).pid();
        assertTrue(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
        runtimes.restart(project.id(), service.id());
        assertNotEquals(firstSession, view(service).pid());
        await(() -> !ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
        runtimes.stop(project.id(), service.id());
        assertEquals("stopped", view(service).state());
    }
    @Test void forceStopsCommandsThatIgnoreTerm() throws Exception {
        Service service = add("trap '' TERM; echo ready; while true; do sleep 1; done");
        runtimes.start(project.id(), service.id());
        await(() -> runtimes.logs(project.id(), service.id(), 0).entries().stream().anyMatch(l -> l.message().equals("ready")));
        long pid = view(service).pid();
        assertTimeoutPreemptively(Duration.ofSeconds(7), () -> runtimes.stop(project.id(), service.id()));
        assertEquals("stopped", view(service).state());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
    @Test void preventsDuplicateStartsAndEditsWhileRunning() throws Exception {
        Service service = add("sleep 120");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> { try { runtimes.start(project.id(), service.id()); return true; } catch (ApiException e) { return false; } });
            var two = executor.submit(() -> { try { runtimes.start(project.id(), service.id()); return true; } catch (ApiException e) { return false; } });
            assertNotEquals(one.get(), two.get());
        }
        assertEquals(409, assertThrows(ApiException.class, () -> runtimes.save(project.id(), service.id(), "New", "echo changed", ".", null)).status);
        assertEquals(409, assertThrows(ApiException.class, () -> runtimes.remove(project.id(), service.id())).status);
    }
    @Test void boundsLogsIncludingLinesWithoutNewlines() throws Exception {
        Service service = add("for i in {1..1500}; do echo line-$i; done; head -c 20000 /dev/zero | tr '\\000' x");
        runtimes.start(project.id(), service.id());
        await(() -> view(service).state().equals("exited"));
        Logs response = runtimes.logs(project.id(), service.id(), 0);
        assertTrue(response.truncated());
        assertTrue(response.entries().size() <= RuntimeManager.LOG_LIMIT);
        assertTrue(response.entries().stream().allMatch(l -> l.message().length() <= 4096));
        assertEquals(0, runtimes.logs(project.id(), service.id(), response.next()).entries().size());
    }
    @Test void persistsDefinitionsWithoutResumingCommands() throws Exception {
        Service service = add("sleep 120");
        runtimes.start(project.id(), service.id());
        ProjectStore restored = new ProjectStore(temporary.resolve("data"));
        try (RuntimeManager fresh = new RuntimeManager(restored, launcher)) {
            assertEquals(service, restored.service(project.id(), service.id()));
            assertEquals("idle", fresh.projects().getFirst().services().getFirst().state());
            assertTrue(fresh.logs(project.id(), service.id(), 0).entries().isEmpty());
        }
    }
    @Test void rejectsEscapingDirectoriesIncludingSymlinks() throws Exception {
        Path outside = Files.createTempDirectory("runtimebay-outside-");
        try {
            Files.createSymbolicLink(temporary.resolve("escape"), outside);
            assertEquals(400, assertThrows(ApiException.class, () -> store.saveService(project.id(), null, "Escape", "echo bad", "escape", null)).status);
            assertThrows(ApiException.class, () -> store.saveService(project.id(), null, "Absolute", "echo bad", outside.toString(), null));
            assertThrows(ApiException.class, () -> store.saveService(project.id(), null, "Port", "echo bad", ".", 65536));
        } finally { Files.deleteIfExists(outside); }
    }
    @Test void supervisorCleansUpWhenOwnerPipeDisappears() throws Exception {
        Path pidFile = temporary.resolve("watchdog.pid");
        Process owner = new ProcessBuilder(launcher.toString(), "sleep 120 & echo $! > '" + pidFile + "'; wait").start();
        try {
            owner.inputReader().readLine();
            await(() -> Files.exists(pidFile));
            long child = Long.parseLong(Files.readString(pidFile).trim());
            owner.getOutputStream().close();
            assertTrue(owner.waitFor(5, TimeUnit.SECONDS));
            await(() -> !ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
        } finally { if (owner.isAlive()) owner.destroyForcibly(); }
    }
    @Test void stripsRuntimeBayTokenFromManagedEnvironment() throws Exception {
        Service service = add("if env | /usr/bin/grep '^RUNTIMEBAY_'; then exit 1; else echo isolated; fi");
        runtimes.start(project.id(), service.id());
        await(() -> view(service).state().equals("exited") || view(service).state().equals("failed"));
        assertEquals("exited", view(service).state());
    }
    @Test void identifiesManagedAndExternalPortListenersWithoutTerminatingExternalOwner() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of("target/test-classes").toAbsolutePath().toString();
        Process external = new ProcessBuilder(java, "-cp", classes, "dev.runtimebay.ListeningFixture", "" + port).start();
        try {
            assertEquals("ready", external.inputReader().readLine());
            PortInspector inspector = new PortInspector(launcher);
            Port observed = inspector.inspect(port, null);
            assertEquals("listening", observed.status());
            assertTrue(observed.owners().stream().anyMatch(o -> o.pid() == external.pid() && !o.managed()));
            Service unrelated = add("sleep 120");
            runtimes.start(project.id(), unrelated.id());
            runtimes.stop(project.id(), unrelated.id());
            assertTrue(external.isAlive());
        } finally { external.destroyForcibly().waitFor(); }
        Service service = store.saveService(project.id(), null, "Listener", "'" + java + "' -cp '" + classes + "' dev.runtimebay.ListeningFixture " + port, ".", port);
        runtimes.start(project.id(), service.id());
        await(() -> view(service).portInfo().owners().stream().anyMatch(Owner::managed));
        runtimes.stop(project.id(), service.id());
        await(() -> view(service).portInfo().status().equals("available"));
    }
    @Test void shellExitCleansUpBackgroundProcesses() throws Exception {
        Path pidFile = temporary.resolve("background.pid");
        Service service = add("sleep 120 & echo $! > '" + pidFile + "'; exit 0");
        runtimes.start(project.id(), service.id());
        await(() -> view(service).state().equals("exited"));
        long child = Long.parseLong(Files.readString(pidFile).trim());
        await(() -> !ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
    }
}

package dev.runtimebay;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MachineHostsTest {
    @TempDir Path directory;
    @Test void cachesInspectionsAndDiscardsResultsFromEditedConnections() throws Exception {
        HostRegistry registry = new HostRegistry(directory); var host = registry.save(null, "Helfer", "helfer", "", null);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var machines = new MachineHosts(registry, new HostTools(args -> { started.countDown(); release.await(); return new HostTools.Result(0, HostToolsTest.fixture()); }))) {
            assertEquals("scanning", machines.inspect(host.id()).state()); assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals("scanning", machines.inspect(host.id()).state());
            machines.save(host.id(), "Helfer", "new-helfer", "", 22); release.countDown();
            assertEquals("idle", machines.status(host.id()).state());
            machines.inspect(host.id());
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> { while (machines.status(host.id()).state().equals("scanning")) Thread.sleep(10); });
            assertEquals("ready", machines.status(host.id()).state()); assertNotNull(machines.status(host.id()).inventory());
            machines.remove(host.id()); assertThrows(ApiException.class, () -> machines.status(host.id()));
        }
    }
}

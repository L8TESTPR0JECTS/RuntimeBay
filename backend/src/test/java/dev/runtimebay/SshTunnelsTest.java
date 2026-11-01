package dev.runtimebay;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SshTunnelsTest {
    @TempDir Path temporary;

    @Test void savedForwardingProfilesSurviveReloadAndKeepTheirHostBinding() throws Exception {
        var registry = new HostRegistry(temporary);
        var host = registry.save(null, "Helfer", "helfer", "alek", 22);
        var hosts = new MachineHosts(registry, new HostTools());
        var tunnels = new SshTunnels(temporary, hosts, null, temporary);
        var saved = tunnels.save(null, "Gobble MySQL", host.id(), 13306, "localhost", 3306, "helfer-remote");
        assertEquals("stopped", saved.state());
        assertEquals("Gobble MySQL", tunnels.list().getFirst().definition().name());
        assertThrows(ApiException.class, () -> tunnels.save(null, "Collision", host.id(), 13306, "localhost", 80, null));
        assertThrows(ApiException.class, () -> tunnels.requireUnusedHost(host.id()));
        tunnels.close();

        var restored = new SshTunnels(temporary, hosts, null, temporary);
        assertEquals(saved.definition(), restored.list().getFirst().definition());
        assertEquals("helfer-remote", restored.list().getFirst().definition().sshAlias());
        assertEquals("Helfer", restored.list().getFirst().hostName());
        restored.close(); hosts.close();
    }

    @Test void rejectsUnsafeDestinationsAndInvalidPorts() {
        assertThrows(ApiException.class, () -> SshTunnels.validate("bad", "host-id", 1234, "-oProxyCommand=evil", 80));
        assertThrows(ApiException.class, () -> SshTunnels.validate("bad", "host-id", 0, "localhost", 80));
        assertThrows(ApiException.class, () -> SshTunnels.validate("bad", "host-id", 1234, "localhost", 65536));
        assertThrows(ApiException.class, () -> SshTunnels.validate("bad", "host-id", 1234, "localhost", 80, "-oProxyCommand=evil"));
        assertDoesNotThrow(() -> SshTunnels.validate("Tunnel", "host-id", 19789, "localhost", 19789, "helfer-remote"));
    }

    @Test void sshAliasOverridesHostAuthTargetButKeepsControlOptions() {
        var host = new HostRegistry.Host("123", "Helfer", "helfer", "alek", 22, false);
        var args = HostRegistry.sshArguments(host, false, Path.of("/tmp/ssh-controls"), "helfer-remote");
        assertTrue(args.contains("helfer-remote"));
        assertFalse(args.contains("alek@helfer"));
        assertFalse(args.contains("-p"));
        assertTrue(args.stream().anyMatch(arg -> arg.startsWith("ControlPath=")));
    }
}

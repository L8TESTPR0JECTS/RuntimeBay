package dev.runtimebay;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import static org.junit.jupiter.api.Assertions.*;

class HostRegistryTest {
    @TempDir Path directory;
    @Test void savesIndependentMachineIdentitiesAndDefaultsAndPreservesIdsOnRename() throws Exception {
        HostRegistry registry = new HostRegistry(directory);
        assertEquals("This Mac", registry.get("local").name());
        var host = registry.save(null, "Helfer", "helfer", "alek", 22);
        registry.makeDefault(host.id());
        var renamed = registry.save(host.id(), "Helfer LAN node", "192.168.0.122", "alek", 2222);
        HostRegistry reloaded = new HostRegistry(directory);
        assertEquals(host.id(), renamed.id()); assertEquals(renamed, reloaded.get(host.id())); assertEquals(host.id(), reloaded.view().defaultHostId());
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("hosts.json")));
        assertEquals(409, assertThrows(ApiException.class, () -> reloaded.remove(host.id())).status);
        reloaded.makeDefault("local"); reloaded.remove(host.id()); assertEquals(1, new HostRegistry(directory).view().hosts().size());
        assertThrows(ApiException.class, () -> reloaded.remove("local"));
    }
    @Test void validatesLiteralSshTargetsWithoutPasswordsFlagsOrShellFragments() {
        for (String address : new String[]{"-oProxyCommand=bad", "host;touch file", "user@host", "ssh://user@host", "host\nnext", "$(whoami)", "host/path"})
            assertThrows(ApiException.class, () -> HostRegistry.validate("Host", address, "", null), address);
        assertThrows(ApiException.class, () -> HostRegistry.validate("Host", "helfer", "user:password", 22));
        assertThrows(ApiException.class, () -> HostRegistry.validate("Host", "helfer", "", 65536));
        assertDoesNotThrow(() -> HostRegistry.validate("Helfer", "helfer", "", null));
        assertDoesNotThrow(() -> HostRegistry.validate("IPv6", "::1", "root", 22));
        var host = new HostRegistry.Host("fixture", "Helfer", "helfer", "alek", 22, false);
        var args = HostRegistry.sshArguments(host, false);
        assertEquals("/usr/bin/ssh", args.getFirst()); assertEquals("alek@helfer", args.getLast());
        assertTrue(args.contains("StrictHostKeyChecking=yes")); assertTrue(args.contains("BatchMode=yes")); assertFalse(HostRegistry.sshArguments(host, true).contains("BatchMode=yes"));
        var controls = Path.of("/tmp/runtimebay-ssh-fixture");
        var probe = HostRegistry.sshArguments(host, false, controls);
        var terminal = HostRegistry.sshArguments(host, true, controls);
        assertTrue(probe.contains("ControlPath=" + controls.resolve("fixture-%C")));
        assertTrue(terminal.contains("ControlPath=" + controls.resolve("fixture-%C")));
        assertTrue(terminal.contains("ControlPersist=no")); assertTrue(probe.contains("ControlMaster=auto"));
    }
    @Test void preservesInvalidRegistryInsteadOfSilentlyReplacingMachines() throws Exception {
        Path file = directory.resolve("hosts.json"); Files.writeString(file, "broken");
        assertThrows(java.io.IOException.class, () -> new HostRegistry(directory)); assertEquals("broken", Files.readString(file));
    }
}

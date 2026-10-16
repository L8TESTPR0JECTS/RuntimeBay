package dev.runtimebay;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class HostToolsTest {
    static String fixture() {
        StringBuilder output = new StringBuilder("Login banner\nRB_MACHINE\thelfer\tLinux\tx86_64\n");
        for (String tool : List.of("python3", "pip3", "java", "javac", "node", "npm", "aws", "git", "docker", "mvn", "gradle", "pnpm", "yarn", "go")) {
            if (tool.equals("docker")) output.append("RB_TOOL\tdocker\tmissing\t\t\n");
            else output.append("RB_TOOL\t").append(tool).append("\tavailable\t/usr/bin/").append(tool).append("\tfixture version 1.2\n");
        }
        return output.append("RB_PROCESS\t123\tjava\nRB_PROCESS\t456\tnode\n").toString();
    }
    @Test void inspectsRemoteMachinesOverSshEvenWhenDockerIsMissing() throws Exception {
        var captured = new AtomicReference<List<String>>();
        try (var tools = new HostTools(args -> { captured.set(args); return new HostTools.Result(0, fixture()); })) {
            var inventory = tools.inspect(new HostRegistry.Host("fixture", "Helfer", "helfer", "alek", 22, false));
            assertEquals("helfer", inventory.hostname()); assertEquals("Linux", inventory.os()); assertEquals(14, inventory.tools().size());
            assertEquals("missing", inventory.tools().stream().filter(t -> t.id().equals("docker")).findFirst().orElseThrow().state());
            assertEquals(2, inventory.processes().size()); assertEquals(123, inventory.processes().getFirst().pid());
            assertEquals("/usr/bin/ssh", captured.get().getFirst()); assertTrue(captured.get().contains("alek@helfer"));
            assertTrue(captured.get().getLast().contains("rb_probe python3 --version")); assertFalse(captured.get().contains("--context"));
        }
    }
    @Test void distinguishesSshFailuresAndIncompleteInventories() {
        try (var tools = new HostTools(args -> new HostTools.Result(255, "Connection closed"))) {
            var failure = assertThrows(ApiException.class, () -> tools.inspect(new HostRegistry.Host("fixture", "Helfer", "helfer", "", null, false)));
            assertTrue(failure.getMessage().contains("SSH inspection failed")); assertTrue(failure.getMessage().contains("Connection closed"));
        }
        assertThrows(ApiException.class, () -> HostTools.parse("fixture", "Login banner only"));
        try (var tools = new HostTools(args -> { assertEquals(List.of("/bin/zsh", "-lic", HostTools.SCRIPT), args); return new HostTools.Result(0, fixture()); })) {
            assertDoesNotThrow(() -> tools.inspect(HostRegistry.LOCAL));
        }
    }
    @Test void closedInspectorDoesNotStartAnotherCommand() {
        var tools = new HostTools(args -> { fail("A closed inspector must not execute commands"); return null; });
        tools.close();
        assertEquals(503, assertThrows(ApiException.class, () -> tools.inspect(HostRegistry.LOCAL)).status);
    }
}

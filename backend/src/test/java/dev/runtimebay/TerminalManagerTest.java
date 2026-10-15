package dev.runtimebay;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class TerminalManagerTest {
    @TempDir Path temporary;
    TerminalManager terminals;
    ProjectStore store;
    @BeforeEach void setup() throws Exception {
        store = new ProjectStore(temporary.resolve("data"));
        Path launcher = Path.of(System.getenv().getOrDefault("RUNTIMEBAY_LAUNCHER", "../.build/runtimebay-launcher")).resolveSibling("runtimebay-terminal");
        terminals = new TerminalManager(store, launcher);
    }
    @AfterEach void close() { terminals.close(); }
    String await(String id, String marker) {
        StringBuilder text = new StringBuilder(); long[] cursor = {0};
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            while (!text.toString().contains(marker)) {
                var output = terminals.output(id, cursor[0]); cursor[0] = output.next();
                text.append(new String(Base64.getDecoder().decode(output.data()), StandardCharsets.UTF_8));
                if (output.exitCode() != null && !text.toString().contains(marker)) fail("Shell exited before " + marker + ": " + text);
            }
        }, () -> "Terminal output: " + text);
        return text.toString();
    }
    @Test void providesRealPtyProjectDirectoryInputAndIndependentShells() throws Exception {
        var project = store.addProject("PTY fixture", temporary.toString());
        var first = terminals.create(project.id()); var second = terminals.create(project.id());
        assertNotEquals(first.id(), second.id()); assertEquals(2, terminals.list().size());
        terminals.input(first.id(), "export BAY_TAB_TEST=first; printf '\\nTTY_CHECK:'; test -t 0 && printf yes; printf '\\nCWD_CHECK:'; pwd; printf '\\nRESULT:%s\\n' \"$BAY_TAB_TEST\"\n");
        String output = await(first.id(), "RESULT:first");
        assertTrue(output.contains("TTY_CHECK:yes"), output); assertTrue(output.contains("CWD_CHECK:" + temporary.toRealPath()), output);
        terminals.input(second.id(), "printf '\\nISOLATED:%s\\n' \"${BAY_TAB_TEST-unset}\"\n");
        assertTrue(await(second.id(), "ISOLATED:unset").contains("ISOLATED:unset"));
        terminals.remove(first.id()); assertEquals(1, terminals.list().size());
        assertThrows(ApiException.class, () -> terminals.input(first.id(), "echo no\n"));
        terminals.input(second.id(), "printf '\\nSTILL_ALIVE:%s\\n' yes\n"); await(second.id(), "STILL_ALIVE:yes");
    }
    @Test void propagatesWindowSizeAndControlCWithoutKillingShell() throws Exception {
        var shell = terminals.create(null);
        terminals.resize(shell.id(), 113, 37);
        terminals.input(shell.id(), "printf '\\nSIZE_CHECK:'; stty size; sleep 30\n");
        assertTrue(await(shell.id(), "SIZE_CHECK:37 113").contains("SIZE_CHECK:37 113"));
        Thread.sleep(150); // Allow the shell to hand foreground control to sleep.
        terminals.input(shell.id(), "\u0003");
        Thread.sleep(150); // Let the terminal driver flush the interrupted foreground job before the next user input.
        terminals.input(shell.id(), "printf '\\nINTERRUPTED:%s\\n' yes\n");
        await(shell.id(), "INTERRUPTED:yes");
        assertNull(terminals.list().getFirst().exitCode());
    }
    @Test void closingTabCleansBackgroundJobsInSeparateProcessGroups() throws Exception {
        var shell = terminals.create(null); Path pid = temporary.resolve("child.pid");
        terminals.input(shell.id(), "sleep 120 & print -r -- $! > '" + pid + "'\n");
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> { while (!Files.exists(pid)) Thread.sleep(20); });
        long child = Long.parseLong(Files.readString(pid).trim()); assertTrue(ProcessHandle.of(child).orElseThrow().isAlive());
        terminals.remove(shell.id());
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> { while (ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)) Thread.sleep(20); });
    }
    @Test void preservesExitOutputAndValidatesInputs() throws Exception {
        var shell = terminals.create(null);
        assertThrows(ApiException.class, () -> terminals.resize(shell.id(), 1, 24));
        assertThrows(ApiException.class, () -> terminals.input(shell.id(), "x".repeat(16385)));
        terminals.input(shell.id(), "printf '\\nFINAL_OUTPUT:%s\\n' yes; exit 7\n");
        await(shell.id(), "FINAL_OUTPUT:yes");
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> { while (terminals.list().getFirst().exitCode() == null) Thread.sleep(20); });
        assertEquals(7, terminals.list().getFirst().exitCode());
        assertThrows(ApiException.class, () -> terminals.input(shell.id(), "no"));
        assertThrows(ApiException.class, () -> terminals.output(shell.id(), Long.MAX_VALUE));
    }
    @Test void noSmallArtificialTabLimitAndShutdownStopsAllShells() throws Exception {
        for (int i = 0; i < 12; i++) terminals.create(null);
        assertEquals(12, terminals.list().size()); terminals.close(); assertTrue(terminals.list().isEmpty());
        assertThrows(ApiException.class, () -> terminals.create(null));
    }
}

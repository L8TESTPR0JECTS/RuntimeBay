package dev.runtimebay;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.ServerSocketChannel;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class HostPasswordsTest {
    Path helper() { return Path.of(System.getenv().getOrDefault("RUNTIMEBAY_LAUNCHER", "../.build/runtimebay-launcher")).resolveSibling("runtimebay-askpass"); }
    ProcessBuilder configured(HostPasswords passwords) {
        var builder = new ProcessBuilder(new ArrayList<>(List.of("/usr/bin/ssh", "-o", "BatchMode=yes", "--", "fixture")));
        passwords.configure(builder, "fixture"); return builder;
    }
    String ask(ProcessBuilder configured, String prompt, boolean success) throws Exception {
        var builder = new ProcessBuilder(helper().toString(), prompt);
        builder.environment().putAll(configured.environment());
        Process child = builder.start();
        try {
            assertTrue(child.waitFor(7, TimeUnit.SECONDS));
            assertEquals(success ? 0 : 1, child.exitValue());
            assertEquals("", new String(child.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            return new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } finally { child.destroyForcibly(); }
    }
    @Test void passwordUsesPrivateAskpassAndIsNeitherAnArgumentNorAnEnvironmentValue() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("/tmp"), "rb-");
        try (var passwords = new HostPasswords(directory, helper())) {
            String password = "pāss '$(literal)' 🦉";
            passwords.set("fixture", password); var configured = configured(passwords);
            assertFalse(configured.command().contains("BatchMode=yes")); assertTrue(configured.command().contains("BatchMode=no"));
            assertFalse(configured.command().toString().contains(password)); assertFalse(configured.environment().containsValue(password));
            assertEquals(password + "\n", ask(configured, "fixture's password: ", true));
            assertEquals("", ask(configured, "Enter passphrase for key: ", false));
            passwords.set("fixture", "replacement");
            assertEquals("", ask(configured, "password:", false));
            assertEquals("replacement\n", ask(configured(passwords), "password:", true));
            passwords.clear("fixture"); assertFalse(passwords.has("fixture"));
            assertEquals("", ask(configured, "password:", false));
            assertFalse(configured(passwords).environment().containsKey("RUNTIMEBAY_SSH_TOKEN"));
        } finally { assertFalse(Files.exists(directory.resolve("a"))); Files.delete(directory); }
    }
    @Test void validatesPasswordsAndRejectsUnknownCapabilities() throws Exception {
        assertThrows(ApiException.class, () -> HostPasswords.validate("line\nbreak"));
        assertThrows(ApiException.class, () -> HostPasswords.validate("x".repeat(4097)));
        Path directory = Files.createTempDirectory(Path.of("/tmp"), "rb-");
        try (var passwords = new HostPasswords(directory, helper())) {
            passwords.set("fixture", "secret"); var configured = configured(passwords);
            configured.environment().put("RUNTIMEBAY_SSH_TOKEN", "0".repeat(64));
            assertEquals("", ask(configured, "password:", false));
            assertEquals(Set.of("a"), new HashSet<>(Files.list(directory).map(p -> p.getFileName().toString()).toList()));
        } finally { Files.delete(directory); }
    }
    @Test void sharedSocketWithOpenSshTemporarySuffixFitsMacOsSunPath() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("/tmp"), "rb-");
        try {
            var host = new HostRegistry.Host("548d044d-b24d-474c-abcd-000000000000", "Helfer", "helfer", "", null, false);
            String argument = HostRegistry.sshArguments(host, false, directory).stream().filter(s -> s.startsWith("ControlPath=")).findFirst().orElseThrow();
            String expanded = argument.substring("ControlPath=".length()).replace("%C", "0".repeat(40)) + ".h5p3F2VoUPgA7sVi";
            assertTrue(expanded.getBytes(StandardCharsets.UTF_8).length < 104, expanded);
            try (var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) { listener.bind(UnixDomainSocketAddress.of(expanded)); }
            Files.delete(Path.of(expanded));
        } finally { Files.delete(directory); }
    }
}

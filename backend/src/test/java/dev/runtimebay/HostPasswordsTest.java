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
    static final class MemoryKeychain implements HostPasswords.Persistence {
        final Map<String,String> values=new HashMap<>();
        public boolean available(){return true;}
        public boolean has(HostRegistry.Host host){return values.containsKey(HostKeychain.account(host));}
        public String read(HostRegistry.Host host){return values.get(HostKeychain.account(host));}
        public void write(HostRegistry.Host host,String password){values.put(HostKeychain.account(host),password);}
        public void delete(HostRegistry.Host host){values.remove(HostKeychain.account(host));}
    }
    @Test void rememberedPasswordRestoresAfterRestartOnlyForTheSameMachineDestination()throws Exception{
        var keychain=new MemoryKeychain();var host=new HostRegistry.Host(UUID.randomUUID().toString(),"Helfer","fixture.invalid","alek",22,false);
        Path first=Files.createTempDirectory(Path.of("/tmp"),"rb-");
        try(var passwords=new HostPasswords(first,helper(),keychain)){
            passwords.update(host,"remembered-secret",true,false);assertTrue(passwords.authentication(host).remembered());
        }finally{Files.delete(first);}
        Path second=Files.createTempDirectory(Path.of("/tmp"),"rb-");
        try(var passwords=new HostPasswords(second,helper(),keychain)){
            assertFalse(passwords.has(host.id()));passwords.restore(host);assertTrue(passwords.has(host.id()));
            assertTrue(passwords.authentication(host).remembered());
            var changed=new HostRegistry.Host(host.id(),host.name(),"replacement.invalid",host.user(),host.port(),false);
            passwords.clear(host.id());passwords.restore(changed);assertFalse(passwords.has(host.id()));
            passwords.update(host,"session-only-replacement",false,false);assertFalse(keychain.has(host));assertTrue(passwords.has(host.id()));
            passwords.update(host,"remember-again",true,false);passwords.forget(host);assertFalse(keychain.has(host));assertFalse(passwords.has(host.id()));
        }finally{Files.delete(second);}
    }
    @Test void sessionPasswordDoesNotPersistAndDestinationIncludesAccountAndPort()throws Exception{
        var keychain=new MemoryKeychain();var host=new HostRegistry.Host(UUID.randomUUID().toString(),"Helfer","fixture.invalid","alek",22,false);
        Path directory=Files.createTempDirectory(Path.of("/tmp"),"rb-");
        try(var passwords=new HostPasswords(directory,helper(),keychain)){
            passwords.update(host,"session-secret",false,false);assertTrue(keychain.values.isEmpty());assertTrue(passwords.has(host.id()));
            passwords.update(host,"",false,false);assertTrue(passwords.has(host.id()));
            passwords.forget(host);assertFalse(passwords.has(host.id()));
        }finally{Files.delete(directory);}
        assertNotEquals(HostKeychain.account(host),HostKeychain.account(new HostRegistry.Host(host.id(),host.name(),host.address(),"other",22,false)));
        assertNotEquals(HostKeychain.account(host),HostKeychain.account(new HostRegistry.Host(host.id(),host.name(),host.address(),host.user(),2222,false)));
    }

    @Test void keychainWriteFailureDoesNotInstallASessionPasswordOrClaimSuccess()throws Exception{
        var host=new HostRegistry.Host(UUID.randomUUID().toString(),"Fixture","fixture.invalid","alek",22,false);
        HostPasswords.Persistence denied=new HostPasswords.Persistence(){
            public boolean available(){return true;}public boolean has(HostRegistry.Host h){return false;}public String read(HostRegistry.Host h){return null;}
            public void write(HostRegistry.Host h,String p){throw new ApiException(503,"Keychain denied");}public void delete(HostRegistry.Host h){throw new ApiException(503,"Keychain denied");}
        };
        Path directory=Files.createTempDirectory(Path.of("/tmp"),"rb-");
        try(var passwords=new HostPasswords(directory,helper(),denied)){
            assertEquals(503,assertThrows(ApiException.class,()->passwords.update(host,"not-saved",true,false)).status);
            assertFalse(passwords.authentication(host).remembered());assertFalse(passwords.has(host.id()));
            passwords.set(host.id(),"existing-session");assertThrows(ApiException.class,()->passwords.forget(host));assertFalse(passwords.has(host.id()));
        }finally{Files.delete(directory);}
    }

}

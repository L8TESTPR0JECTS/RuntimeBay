package dev.runtimebay;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import java.util.concurrent.*;

/** Saved terminal descriptors, with lazily opened app-owned PTYs. Commands and output are never persisted. */
final class TerminalManager implements AutoCloseable {
    record View(String id, String name, String directory, Integer exitCode, String hostId, String hostName, boolean connected) {}
    record Output(String data, long next, boolean truncated, Integer exitCode) {}
    private final ProjectStore projects;
    private final Path launcher;
    private final HostPasswords passwords;
    private final LinkedHashMap<String, Shell> shells = new LinkedHashMap<>();
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    record SavedTab(String id,String name,String directory,String hostId,String hostName) {}
    private record Registry(int version,List<SavedTab> tabs) {}
    private final LinkedHashMap<String,SavedTab> saved = new LinkedHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private int number;
    private boolean closed;
    TerminalManager(ProjectStore projects, Path launcher) { this(projects, launcher, null); }
    TerminalManager(ProjectStore projects, Path launcher, HostPasswords passwords) { this.projects = projects; this.launcher = launcher; this.passwords = passwords;
        Path file=projects.directory().resolve("terminals.json");
        if(Files.exists(file))try{
            var registry=gson.fromJson(Files.readString(file),Registry.class);
            if(registry==null||registry.version()!=1||registry.tabs()==null)throw new IllegalArgumentException();
            for(var tab:registry.tabs()){
                if(tab==null||!UUID.fromString(tab.id()).toString().equals(tab.id())||saved.containsKey(tab.id())||tab.directory()==null||tab.hostId()==null||tab.hostName()==null)throw new IllegalArgumentException();
                validateName(tab.name());saved.put(tab.id(),tab);
            }
            number=saved.size();
        }catch(Exception error){throw new IllegalStateException("Cannot read saved terminal tabs. Existing data is preserved.",error);}
    }
    synchronized List<View> list() { if(closed)return List.of();return saved.values().stream().map(tab -> shells.containsKey(tab.id()) ? shells.get(tab.id()).view() : new View(tab.id(),tab.name(),tab.directory(),null,tab.hostId(),tab.hostName(),false)).toList(); }
    synchronized View create(String projectId) throws IOException {
        Path directory = projectId == null || projectId.isBlank() ? Path.of(System.getProperty("user.home")) : Path.of(projects.project(projectId).path());
        return createSaved(directory,List.of(),"local","This Mac");
    }
    synchronized View createRemote(HostRegistry.Host host) throws IOException {
        return createRemote(host, null);
    }
    synchronized View createRemote(HostRegistry.Host host, Path controls) throws IOException {
        if (host.local()) return create(null);
        return createSaved(Path.of(System.getProperty("user.home")), HostRegistry.sshArguments(host, true, controls), host.id(), host.name());
    }
    private View createSaved(Path directory,List<String> command,String hostId,String hostName)throws IOException {
        var tab=new SavedTab(UUID.randomUUID().toString(),(hostId.equals("local")?"":hostName+" · ")+"Terminal "+(++number),hostId.equals("local")?directory.toString():command.getLast(),hostId,hostName);
        var view=launch(tab,directory,command);saved.put(tab.id(),tab);
        try{persist();}catch(IOException error){saved.remove(tab.id());shells.remove(tab.id()).close();throw error;}return view;
    }
    synchronized SavedTab definition(String id){var tab=saved.get(id);if(tab==null)throw new ApiException(404,"Terminal not found");return tab;}
    synchronized View open(String id,HostRegistry.Host host,Path controls)throws IOException {
        var tab=definition(id);if(shells.containsKey(id))return shells.get(id).view();
        if(!host.id().equals(tab.hostId()))throw new ApiException(409,"The saved terminal host changed");
        return launch(tab,host.local()?Path.of(tab.directory()):Path.of(System.getProperty("user.home")),host.local()?List.of():HostRegistry.sshArguments(host,true,controls));
    }
    synchronized View rename(String id,String name)throws IOException {
        validateName(name);var old=definition(id);var tab=new SavedTab(id,name.trim(),old.directory(),old.hostId(),old.hostName());saved.put(id,tab);
        try{persist();}catch(IOException error){saved.put(id,old);throw error;}
        var shell=shells.get(id);if(shell!=null)synchronized(shell){shell.name=tab.name();}
        return list().stream().filter(v->v.id().equals(id)).findFirst().orElseThrow();
    }
    private static void validateName(String name){if(name==null||name.isBlank()||name.length()>128||name.chars().anyMatch(Character::isISOControl))throw new ApiException(400,"Enter a terminal name of up to 128 characters");}
    private void persist()throws IOException{DefinitionTransaction.write(projects.directory().resolve("terminals.json"),gson.toJson(new Registry(1,List.copyOf(saved.values()))).getBytes(StandardCharsets.UTF_8));}
    private View launch(SavedTab tab,Path directory, List<String> command) throws IOException {
        String hostId=tab.hostId(),hostName=tab.hostName();
        if (closed) throw new ApiException(503, "Terminals are shutting down");
        if (!Files.isDirectory(directory)) throw new ApiException(400, "The terminal folder is no longer available");
        if (!Files.isExecutable(launcher)) throw new ApiException(503, "The terminal supervisor is missing. Rebuild Runtime Bay.");
        var arguments = new ArrayList<>(List.of(launcher.toAbsolutePath().toString())); arguments.addAll(command);
        ProcessBuilder builder = new ProcessBuilder(arguments).directory(directory.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("RUNTIMEBAY_"));
        builder.environment().put("TERM", "xterm-256color"); builder.environment().put("COLORTERM", "truecolor");
        if (passwords != null && !hostId.equals("local")) passwords.configure(builder, hostId);
        // Never leave an unread stderr pipe behind; diagnostics do not contain shell output.
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Shell shell = new Shell(tab.id(),tab.name(),tab.directory(),builder.start(),hostId,hostName);
        shells.put(shell.id, shell); readers.submit(shell::read);
        return shell.view();
    }
    private synchronized Shell shell(String id) {
        Shell shell = shells.get(id); if (shell == null) throw new ApiException(404, "Terminal not found"); return shell;
    }
    Output output(String id, long after) throws InterruptedException { return shell(id).output(after); }
    void input(String id, String data) throws IOException {
        if (data == null) throw new ApiException(400, "Terminal input is required");
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 16384) throw new ApiException(400, "Terminal input exceeds 16 KiB");
        shell(id).send('I', bytes);
    }
    void resize(String id, int columns, int rows) throws IOException {
        if (columns < 2 || columns > 1000 || rows < 1 || rows > 1000) throw new ApiException(400, "Invalid terminal dimensions");
        shell(id).send('R', new byte[]{(byte)(columns >> 8), (byte)columns, (byte)(rows >> 8), (byte)rows});
    }
    synchronized void remove(String id)throws IOException {
        var old=definition(id);saved.remove(id);try{persist();}catch(IOException error){saved.put(id,old);throw error;}
        var shell=shells.remove(id);if(shell!=null)shell.close();
    }
    @Override public void close() {
        List<Shell> current; synchronized (this) { closed = true; current = List.copyOf(shells.values()); shells.clear(); }
        current.parallelStream().forEach(Shell::close); readers.shutdownNow();
    }
    private static final class Shell {
        private static final int CAPACITY = 1024 * 1024;
        final String id, directory;
        String name;
        final Process process;
        final String hostId, hostName;
        final DataOutputStream input;
        final byte[] buffer = new byte[CAPACITY];
        long next;
        Integer exitCode;
        boolean closing;
        Shell(String id, String name, String directory, Process process, String hostId, String hostName) {
            this.id = id; this.name = name; this.directory = directory; this.process = process; this.hostId = hostId; this.hostName = hostName; input = new DataOutputStream(process.getOutputStream());
        }
        synchronized View view() { return new View(id, name, directory, exitCode, hostId, hostName,true); }
        void read() {
            try (InputStream output = process.getInputStream()) {
                byte[] bytes = new byte[16384]; int count;
                while ((count = output.read(bytes)) != -1) synchronized (this) {
                    for (int i = 0; i < count; i++) buffer[(int)((next + i) % CAPACITY)] = bytes[i];
                    next += count; notifyAll();
                }
            } catch (IOException ignored) {
                // A closed PTY is expected when a tab or the application closes.
            } finally {
                try { int status = process.waitFor(); synchronized (this) { exitCode = status; notifyAll(); } }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        synchronized Output output(long after) throws InterruptedException {
            if (after < 0 || after > next) throw new ApiException(400, "Invalid terminal output cursor");
            if (after == next && exitCode == null && !closing) wait(10000);
            long start = Math.max(after, next - CAPACITY);
            byte[] bytes = new byte[(int)(next - start)];
            for (int i = 0; i < bytes.length; i++) bytes[i] = buffer[(int)((start + i) % CAPACITY)];
            return new Output(Base64.getEncoder().encodeToString(bytes), next, start > after, exitCode);
        }
        void send(int type, byte[] bytes) throws IOException {
            synchronized (input) {
                synchronized (this) { if (closing || exitCode != null) throw new ApiException(409, "This shell has exited. Open a new terminal to continue."); }
                input.writeByte(type); input.writeInt(bytes.length); input.write(bytes); input.flush();
            }
        }
        void close() {
            synchronized (this) { closing = true; notifyAll(); }
            try { input.close(); } catch (IOException ignored) {}
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
            } catch (InterruptedException e) { process.destroy(); Thread.currentThread().interrupt(); }
        }
    }
}

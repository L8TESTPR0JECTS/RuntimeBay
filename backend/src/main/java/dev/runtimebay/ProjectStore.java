package dev.runtimebay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.runtimebay.Models.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

final class ProjectStore {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private List<Project> projects;

    ProjectStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        file = directory.resolve("projects.json");
        DefinitionTransaction.recover(directory);
        reload();
    }
    synchronized void reload() throws IOException {
        if (Files.exists(file)) {
            try {
                Registry registry = gson.fromJson(Files.readString(file), Registry.class);
                if (registry == null || registry.version() != 1 || registry.projects() == null)
                    throw new IllegalArgumentException("Unsupported project registry");
                var loaded = new ArrayList<>(registry.projects());
                for (Project project : loaded) {
                    if (project.id() == null || project.path() == null || project.services() == null)
                        throw new IllegalArgumentException("Invalid project registry");
                }
                projects=loaded;
            } catch (RuntimeException e) {
                throw new IOException("Cannot read project registry. Existing data has been preserved.", e);
            }
        } else projects = new ArrayList<>();
    }

    Path directory() { return file.getParent(); }
    synchronized List<Project> list() { return List.copyOf(projects); }
    synchronized Project project(String id) {
        return projects.stream().filter(p -> p.id().equals(id)).findFirst()
            .orElseThrow(() -> new ApiException(404, "Project not found"));
    }
    synchronized Service service(String projectId, String id) {
        return project(projectId).services().stream().filter(s -> s.id().equals(id)).findFirst()
            .orElseThrow(() -> new ApiException(404, "Service not found"));
    }
    synchronized Project addProject(String name, String directory) throws IOException {
        Path root = realDirectory(directory);
        for (Project existing : projects) if (existing.path().equals(root.toString())) return existing;
        String label = name == null || name.isBlank() ? (root.getFileName() == null ? "Root" : root.getFileName().toString()) : text(name, "Project name", 80);
        Project project = new Project(UUID.randomUUID().toString(), label, root.toString(), List.of());
        var updated = new ArrayList<>(projects);
        updated.add(project);
        commit(updated);
        return project;
    }
    synchronized Service saveService(String projectId, String id, String name, String command,
                                     String directory, Integer port) throws IOException {
        Project project = project(projectId);
        if (port != null && (port < 1 || port > 65535)) throw new ApiException(400, "Port must be between 1 and 65535");
        String cwd = directory == null || directory.isBlank() ? "." : directory;
        resolveDirectory(project, cwd);
        Service service = new Service(id == null ? UUID.randomUUID().toString() : id,
            text(name, "Service name", 80), text(command, "Command", 4096), cwd, port);
        var services = new ArrayList<>(project.services());
        if (id != null) {
            service(projectId, id);
            services.removeIf(s -> s.id().equals(id));
        }
        if (services.stream().anyMatch(s -> s.name().equalsIgnoreCase(service.name())))
            throw new ApiException(409, "A service with this name already exists");
        services.add(service);
        replace(project, services);
        return service;
    }
    synchronized void removeService(String projectId, String id) throws IOException {
        Project project = project(projectId);
        service(projectId, id);
        replace(project, project.services().stream().filter(s -> !s.id().equals(id)).toList());
    }
    synchronized void importServices(String projectId, String name, List<Service> services) throws IOException {
        Project original=project(projectId);Set<String> ids=new HashSet<>(),names=new HashSet<>();
        for(var service:services) {
            if(service==null||service.id()==null||!ids.add(service.id())||!names.add(service.name().toLowerCase(Locale.ROOT)))throw new ApiException(400,"Duplicate imported service identity or name");
            text(service.name(),"Service name",80);text(service.command(),"Command",4096);resolveDirectory(original,service.directory());
            if(service.port()!=null&&(service.port()<1||service.port()>65535))throw new ApiException(400,"Invalid service port");
        }
        var next=new ArrayList<>(projects);next.set(next.indexOf(original),new Project(original.id(),text(name,"Project name",80),original.path(),List.copyOf(services)));commit(next);
    }
    private void replace(Project original, List<Service> services) throws IOException {
        var updated = new ArrayList<>(projects);
        updated.set(updated.indexOf(original), new Project(original.id(), original.name(), original.path(), List.copyOf(services)));
        commit(updated);
    }
    private void commit(List<Project> updated) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "projects-", ".tmp");
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(temporary, gson.toJson(new Registry(1, updated)), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            projects = updated;
        } finally { Files.deleteIfExists(temporary); }
    }
    static Path resolveDirectory(Project project, String relative) throws IOException {
        if (Path.of(relative).isAbsolute()) throw new ApiException(400, "Service directory must be relative to the project");
        Path root = realDirectory(project.path());
        Path directory = realDirectory(root.resolve(relative).toString());
        if (!directory.startsWith(root)) throw new ApiException(400, "Service directory must stay inside the project");
        return directory;
    }
    private static Path realDirectory(String value) throws IOException {
        if (value == null || value.isBlank()) throw new ApiException(400, "Choose a project directory");
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw new ApiException(400, "Project path must be absolute");
        try {
            path = path.toRealPath();
            if (!Files.isDirectory(path)) throw new ApiException(400, "Path must be a directory");
            return path;
        } catch (NoSuchFileException e) { throw new ApiException(400, "Directory does not exist"); }
    }
    private static String text(String value, String label, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0)
            throw new ApiException(400, label + " is required (maximum " + max + " characters)");
        return value.trim();
    }
}

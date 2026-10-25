package dev.runtimebay;

import java.util.List;
import java.util.Map;

final class DockerModels {
    record Environment(String name, String description, String endpoint, String transport, boolean supported, String reason, boolean current, String machineName) {
        Environment(String name,String description,String endpoint,String transport,boolean supported,String reason,boolean current){this(name,description,endpoint,transport,supported,reason,current,null);}
    }
    record Discovery(boolean available, List<Environment> environments, String error) {}
    record Engine(String name, String version, String operatingSystem, String architecture, int cpus, long memory, int containers, int running, int images) {}
    record Container(String id, String name, String image, String state, String status, String ports, String created, String composeProject, String workspaceProject) {}
    record Overview(Environment environment, Engine engine, List<Container> containers) {}
    record Image(String id, String repository, String tag, String size, String created) {}
    record Volume(String name, String driver, String scope) {}
    record Network(String id, String name, String driver, String scope) {}
    record Inventory(List<Image> images, List<Volume> volumes, List<Network> networks) {}
    record Metrics(String cpu, String memory, String memoryPercent, String network, String disk, String pids) {}
    record Details(String id, String name, String image, String state, String startedAt, String finishedAt, int exitCode, String restartPolicy, List<String> mounts, List<String> networks, String logs, Metrics metrics, String metricsError) {}
    record PublishedPort(int host, int container) {}
    record Mount(String volume, String path, boolean readOnly) {}
    record Create(String name, String image, List<String> arguments, List<PublishedPort> ports, List<Mount> mounts, String restartPolicy, String projectId, Map<String, String> environment) {}
    record Operation(String id, String context, String endpoint, String image, String state, String output, String error, String createdAt) {}
    private DockerModels() {}
}

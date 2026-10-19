package dev.runtimebay;

import java.util.List;

/** Definitions only. A host reference or saved binding never grants process ownership. */
final class WorkflowModels {
    record Environment(String id, String name) {}
    record MachineReference(String id) {}
    record DockerConnection(String id, String context, String endpoint, String machineId) {}
    record Resource(String id, String type, String sourceId, String name, String ownership) {}
    record Target(String kind, String id, String linkId) {
        Target(String kind, String id) { this(kind, id, null); }
    }
    record ContainerOption(String id, String name, String image, DockerConnection connection) {}
    record Binding(String id, String environmentId, String resourceId, Target target) {}
    record Workflow(String id, String projectId, String name, String defaultEnvironmentId,
                    List<Environment> environments, List<MachineReference> machines,
                    List<DockerConnection> dockerConnections, List<Resource> resources, List<Binding> bindings) {
        Workflow {
            environments = environments == null ? null : List.copyOf(environments);
            machines = machines == null ? null : List.copyOf(machines);
            dockerConnections = dockerConnections == null ? null : List.copyOf(dockerConnections);
            resources = resources == null ? null : List.copyOf(resources);
            bindings = bindings == null ? null : List.copyOf(bindings);
        }
    }
    record Registry(int version, List<Workflow> workflows) {}
    record BindingView(String id, String resourceId, String name, String type, String targetKind,
                       String targetId, String targetName, String status, String detail) {}
    record View(Workflow definition, List<BindingView> resources, String revision) {}
    private WorkflowModels() {}
}

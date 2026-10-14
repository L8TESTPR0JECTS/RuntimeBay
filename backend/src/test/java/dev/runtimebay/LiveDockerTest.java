package dev.runtimebay;

import dev.runtimebay.DockerModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RUNTIMEBAY_LIVE_DOCKER_CONTEXT", matches = ".+")
class LiveDockerTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "RUNTIMEBAY_LIVE_DOCKER_PULL", matches = "1")
    void pullsAnOfficialDockerHubImageWithTrackedProgress() {
        String context = System.getenv("RUNTIMEBAY_LIVE_DOCKER_CONTEXT");
        try (DockerService docker = DockerService.discover()) {
            Environment host = docker.environments().environments().stream().filter(e -> e.name().equals(context)).findFirst().orElseThrow();
            assertEquals("local", host.transport());
            Operation operation = docker.pull(context, host.endpoint(), "docker.io/library/alpine:3.21");
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
                    while (List.of("queued", "running").contains(docker.operation(operation.id()).state())) Thread.sleep(100);
                });
                Operation finished = docker.operation(operation.id());
                assertEquals("succeeded", finished.state(), finished.error());
                assertFalse(finished.output().isBlank());
                assertTrue(docker.inventory(context, host.endpoint()).images().stream().anyMatch(i -> i.repository().endsWith("alpine") && i.tag().equals("3.21")));
            } finally { docker.cancel(operation.id()); }
        }
    }
    @Test void managesOnlyItsOwnDisposableContainerOnTheSelectedEngine() throws Exception {
        String context = System.getenv("RUNTIMEBAY_LIVE_DOCKER_CONTEXT");
        try (DockerService docker = DockerService.discover()) {
            Environment host = docker.environments().environments().stream().filter(e -> e.name().equals(context)).findFirst().orElseThrow();
            assertEquals("local", host.transport(), "Live tests must explicitly select a local Docker engine");
            String name = "runtimebay-test-" + UUID.randomUUID().toString().substring(0, 8);
            String id = docker.create(context, host.endpoint(), new Create(name, "alpine:latest", List.of("sh", "-c", "echo runtimebay-container-test; while true; do sleep 1; done"), List.of(), List.of(), "no", null, Map.of("RUNTIMEBAY_FIXTURE", "only-this-container")));
            boolean removed = false;
            try {
                assertTrue(docker.overview(context, host.endpoint()).containers().stream().anyMatch(c -> c.id().equals(id) && c.state().equals("created")));
                docker.action(context, host.endpoint(), id, "start");
                Details detail = docker.details(context, host.endpoint(), id);
                assertTrue(detail.logs().contains("runtimebay-container-test"));
                assertNotNull(detail.metrics());
                assertEquals("running", detail.state());
                docker.action(context, host.endpoint(), id, "pause");
                assertTrue(docker.overview(context, host.endpoint()).containers().stream().anyMatch(c -> c.id().equals(id) && c.state().equals("paused")));
                docker.action(context, host.endpoint(), id, "unpause");
                docker.action(context, host.endpoint(), id, "restart");
                docker.action(context, host.endpoint(), id, "stop");
                assertEquals("exited", docker.details(context, host.endpoint(), id).state());
                docker.action(context, host.endpoint(), id, "remove");
                assertTrue(docker.overview(context, host.endpoint()).containers().stream().noneMatch(c -> c.id().equals(id)));
                removed = true;
            } finally {
                if (!removed) { try { docker.action(context, host.endpoint(), id, "stop"); } finally { docker.action(context, host.endpoint(), id, "remove"); } }
            }
            Inventory inventory = docker.inventory(context, host.endpoint()); assertFalse(inventory.images().isEmpty()); assertFalse(inventory.networks().isEmpty());
        }
    }
}

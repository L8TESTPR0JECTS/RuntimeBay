package dev.runtimebay;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowPlansTest {
    @TempDir Path temporary;
    ProjectStore projects; RuntimeManager runtimes; WorkflowManifests manifests; WorkflowPlans plans; HostRegistry hosts; String project;
    static final String YAML="""
        apiVersion: runtimebay.dev/v1
        kind: Workflow
        name: Fixture
        processes:
          setup:
            command: printf ready > prepared
            mode: task
          api:
            command: test -f prepared && sleep 120
            dependsOn: [setup]
        environments:
          local: {}
        """;
    @BeforeEach void setup() throws Exception {
        projects=new ProjectStore(temporary.resolve("data"));project=projects.addProject("Fixture",temporary.toString()).id();
        runtimes=new RuntimeManager(projects,Path.of(System.getenv().getOrDefault("RUNTIMEBAY_LAUNCHER","../.build/runtimebay-launcher")).toAbsolutePath());
        manifests=new WorkflowManifests(projects,runtimes);hosts=new HostRegistry(projects.directory());plans=new WorkflowPlans(projects,runtimes,manifests,hosts::view);
    }
    @AfterEach void close(){plans.close();runtimes.close();}
    void apply(String yaml)throws Exception{var p=manifests.preview(project,yaml,Map.of(),hosts.view());manifests.apply(project,yaml,Map.of(),p.revision(),hosts.view());}
    void settled(){assertTimeoutPreemptively(Duration.ofSeconds(12),()->{while(plans.active(project))Thread.sleep(20);});}
    String id(String key){return manifests.get(project).services().get(key);}
    @Test void importDoesNotExecuteAndReviewedPlanRunsTasksBeforeServices()throws Exception{
        apply(YAML);assertFalse(Files.exists(temporary.resolve("prepared")));var preview=plans.preview(project,"");assertEquals(List.of("setup","api"),preview.steps().stream().map(WorkflowPlans.Step::key).toList());
        assertThrows(ApiException.class,()->plans.start(project,"","stale"));assertFalse(Files.exists(temporary.resolve("prepared")));
        plans.start(project,"",preview.revision());settled();assertEquals("completed",plans.state(project).state());assertEquals("ready",Files.readString(temporary.resolve("prepared")));assertEquals("running",runtimes.processState(project,id("api")).state());
    }
    @Test void failedTaskDoesNotStartDependentsAndStopsNewServicesButKeepsReusedOnes()throws Exception{
        String yaml=YAML.replace("command: printf ready > prepared","command: sleep 120").replace("mode: task","mode: service").replace("command: test -f prepared && sleep 120","command: exit 7\n    mode: task");
        apply(yaml);runtimes.start(project,id("setup"));var preview=plans.preview(project,"");assertTrue(preview.steps().getFirst().reuse());plans.start(project,"",preview.revision());settled();
        assertEquals("failed",plans.state(project).state());assertEquals("running",runtimes.processState(project,id("setup")).state());runtimes.stop(project,id("setup"));
        preview=plans.preview(project,"");plans.start(project,"",preview.revision());settled();assertEquals("failed",plans.state(project).state());assertEquals("stopped",runtimes.processState(project,id("setup")).state());
    }
    @Test void cancellationStopsWaitingTaskAndNeverRunsDependent()throws Exception{
        apply(YAML.replace("printf ready > prepared","sleep 120"));var preview=plans.preview(project,"");var run=plans.start(project,"",preview.revision());
        assertTimeoutPreemptively(Duration.ofSeconds(5),()->{while(!runtimes.processState(project,id("setup")).state().equals("running"))Thread.sleep(20);});
        plans.cancel(project,run.id());settled();assertEquals("cancelled",plans.state(project).state());assertEquals("stopped",runtimes.processState(project,id("setup")).state());assertNull(runtimes.processState(project,id("api")).startedAt());
    }
    @Test void missingEnvironmentAndRemoteBindingsFailBeforeAnyProcessStarts()throws Exception{
        apply(YAML.replace("mode: task","mode: task\n    env: {VALUE: '${env:UNSET_RUNTIME_BAY_QA_911486}'}"));
        assertThrows(ApiException.class,()->plans.preview(project,""));assertFalse(Files.exists(temporary.resolve("prepared")));
        assertNull(runtimes.processState(project,id("setup")).startedAt());
    }
}

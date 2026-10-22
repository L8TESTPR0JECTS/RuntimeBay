package dev.runtimebay;

import com.google.gson.Gson;
import dev.runtimebay.WorkflowModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowManifestsTest {
    @TempDir Path temporary;
    ProjectStore projects;RuntimeManager runtimes;WorkflowManifests manifests;HostRegistry hosts;String project;
    String localYaml(){return WorkflowYamlTest.YAML.replace("hosts: [local, node]","hosts: [local]").replace("bindings: {api: node}","bindings: {api: local}");}
    @BeforeEach void setup()throws Exception{projects=new ProjectStore(temporary.resolve("data"));project=projects.addProject("Original",temporary.toString()).id();runtimes=new RuntimeManager(projects,Path.of("/never-used"));manifests=new WorkflowManifests(projects,runtimes);hosts=new HostRegistry(projects.directory());}
    @AfterEach void close(){runtimes.close();}
    @Test void previewsWithoutWritesAppliesIdempotentlyAndExportsNoMachineAddresses()throws Exception{
        var host=hosts.save(null,"Private machine","private-host.invalid","private-user",2222);var mappings=Map.of("node",host.id());byte[] original=Files.readAllBytes(projects.directory().resolve("projects.json"));
        var preview=manifests.preview(project,WorkflowYamlTest.YAML,mappings,hosts.view());assertArrayEquals(original,Files.readAllBytes(projects.directory().resolve("projects.json")));assertFalse(Files.exists(projects.directory().resolve("workflow-manifests.json")));
        var first=manifests.apply(project,WorkflowYamlTest.YAML,mappings,preview.revision(),hosts.view());assertEquals(3,projects.project(project).services().size());assertEquals("Gobble",projects.project(project).name());
        var next=manifests.preview(project,WorkflowYamlTest.YAML,mappings,hosts.view());var second=manifests.apply(project,WorkflowYamlTest.YAML,mappings,next.revision(),hosts.view());assertEquals(first.services(),second.services());assertEquals(first.environments(),second.environments());
        String exported=manifests.export(project).yaml();assertFalse(exported.contains("private-host"));assertFalse(exported.contains("private-user"));assertFalse(exported.contains(host.id()));assertEquals(3,WorkflowYaml.parse(exported).processes().size());
        assertEquals(first,new WorkflowManifests(projects,runtimes).get(project));assertFalse(Files.exists(projects.directory().resolve("manifest-transaction.json")));
    }
    @Test void staleImportAndInvalidDirectoryLeaveDefinitionsUnchanged()throws Exception{
        var preview=manifests.preview(project,localYaml(),Map.of(),hosts.view());projects.saveService(project,null,"Unmanaged","echo unchanged",".",null);
        byte[] before=Files.readAllBytes(projects.directory().resolve("projects.json"));assertThrows(ApiException.class,()->manifests.apply(project,localYaml(),Map.of(),preview.revision(),hosts.view()));assertArrayEquals(before,Files.readAllBytes(projects.directory().resolve("projects.json")));
        assertThrows(ApiException.class,()->manifests.preview(project,localYaml().replace("command: sleep 30","command: sleep 30\n    directory: missing"),Map.of(),hosts.view()));assertArrayEquals(before,Files.readAllBytes(projects.directory().resolve("projects.json")));
    }
    @Test void interruptedPreparedImportRecoversBeforeAnyRegistryIsRead()throws Exception{
        var original=new HashMap<String,byte[]>();for(String name:DefinitionTransaction.FILES){Path p=projects.directory().resolve(name);if(Files.exists(p))original.put(name,Files.readAllBytes(p));}
        new DefinitionTransaction(projects.directory());DefinitionTransaction.write(projects.directory().resolve("projects.json"),"broken".getBytes());DefinitionTransaction.write(projects.directory().resolve("workflow-manifests.json"),"broken".getBytes());
        var recovered=new ProjectStore(projects.directory());assertEquals("Original",recovered.project(project).name());
        for(var item:original.entrySet())assertArrayEquals(item.getValue(),Files.readAllBytes(projects.directory().resolve(item.getKey())));
        assertFalse(Files.exists(projects.directory().resolve("workflow-manifests.json")));assertFalse(Files.exists(projects.directory().resolve("manifest-transaction.json")));
    }
    @Test void importRollsBackEarlierWritesWhenExistingContainerReferencesCannotBeCloned()throws Exception{
        var linksFile=projects.directory().resolve("workflow-containers.json");String own="own-link",other="other-link";
        var ownLink=new WorkflowContainers.Link(own,project,"local","unix:///fixture.sock","a".repeat(64),"Redis","redis");var remoteLink=new WorkflowContainers.Link(other,project,"remote","ssh://fixture.invalid","b".repeat(64),"Remote Redis","redis");
        Files.writeString(linksFile,new Gson().toJson(new WorkflowContainers.Registry(1,List.of(ownLink,remoteLink))));var graph=runtimes.workflows().get(project);var connection=graph.dockerConnections().stream().filter(c->c.context().equals("remote")).findFirst().orElseThrow();
        var bindings=graph.bindings().stream().map(b->b.resourceId().equals(own)?new Binding(b.id(),b.environmentId(),b.resourceId(),new Target("docker",connection.id(),other)):b).toList();
        runtimes.workflows().save(project,new Workflow(graph.id(),project,graph.name(),graph.defaultEnvironmentId(),graph.environments(),graph.machines(),graph.dockerConnections(),graph.resources(),bindings));
        Files.writeString(linksFile,new Gson().toJson(new WorkflowContainers.Registry(1,List.of(ownLink))));runtimes.workflows().get(project);
        var before=new HashMap<String,byte[]>();for(String name:DefinitionTransaction.FILES){Path p=projects.directory().resolve(name);if(Files.exists(p))before.put(name,Files.readAllBytes(p));}
        var preview=manifests.preview(project,localYaml(),Map.of(),hosts.view());assertThrows(ApiException.class,()->manifests.apply(project,localYaml(),Map.of(),preview.revision(),hosts.view()));
        for(var item:before.entrySet())assertArrayEquals(item.getValue(),Files.readAllBytes(projects.directory().resolve(item.getKey())));assertEquals("Original",projects.project(project).name());assertTrue(projects.project(project).services().isEmpty());
    }
    @Test void fileWritesRequireTheCurrentFileHashAndRejectSymlinks()throws Exception{
        assertFalse(manifests.readFile(project).exists());manifests.saveFile(project,localYaml(),"missing");var file=manifests.readFile(project);assertTrue(file.exists());assertThrows(ApiException.class,()->manifests.saveFile(project,localYaml(),"missing"));
        Files.delete(temporary.resolve("runtimebay.yaml"));Path outside=temporary.resolve("outside.yaml");Files.writeString(outside,"do-not-change");Files.createSymbolicLink(temporary.resolve("runtimebay.yaml"),outside);assertThrows(ApiException.class,()->manifests.readFile(project));assertEquals("do-not-change",Files.readString(outside));
    }
    @Test void resourceOnlyManifestRoundTripsLogicalReferencesAcrossEnvironmentsWithoutExecution()throws Exception{
        String source=projects.addProject("Shared",Files.createDirectory(temporary.resolve("shared")).toString()).id();
        var service=projects.saveService(source,null,"Shared API","echo never-run",".",null);
        var containers=new WorkflowContainers(projects,new DockerService(null));
        var link=containers.record(source,"fixture","unix:///fixture.sock","a".repeat(64),"Cache","redis");
        var attachments=new WorkflowAttachments(projects,runtimes.workflows());
        manifests=new WorkflowManifests(projects,runtimes,containers,attachments);
        String yaml="""
            apiVersion: runtimebay.dev/v1
            kind: Workflow
            name: Resources
            resources:
              cache: {kind: container, reference: cache-ref}
              api: {kind: service, reference: api-ref}
              terminal: {kind: terminal, reference: shell-ref}
            environments:
              local: {}
              alternate:
                resources: {terminal: null}
            """;
        var mappings=Map.of("cache-ref",new WorkflowAttachments.Source("container",source,link.id(),""),
            "api-ref",new WorkflowAttachments.Source("service",source,service.id(),""),
            "shell-ref",new WorkflowAttachments.Source("terminal","","local",""));
        var preview=manifests.preview(project,yaml,Map.of(),mappings,hosts.view());assertTrue(attachments.all(project).isEmpty());
        var first=manifests.apply(project,yaml,Map.of(),mappings,preview.revision(),hosts.view());assertEquals(3,attachments.all(project).size());assertEquals(1,containers.list(project).size());
        String exported=manifests.export(project).yaml();assertFalse(exported.contains(link.id()));assertFalse(exported.contains(service.id()));assertFalse(exported.contains("fixture.sock"));
        var definition=WorkflowYaml.parse(exported);assertEquals(3,definition.resources().size());assertEquals(2,definition.environments().size());
        var next=manifests.preview(project,exported,Map.of(),first.references(),hosts.view());var second=manifests.apply(project,exported,Map.of(),first.references(),next.revision(),hosts.view());
        assertEquals(first.resourceIds(),second.resourceIds());assertEquals(first.attachmentIds(),second.attachmentIds());assertEquals(3,attachments.all(project).size());assertEquals(1,containers.list(project).size());
        assertNull(runtimes.processState(source,service.id()).startedAt());
    }

}

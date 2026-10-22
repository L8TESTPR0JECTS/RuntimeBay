package dev.runtimebay;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowYamlTest {
    static final String YAML="""
        apiVersion: runtimebay.dev/v1
        kind: Workflow
        name: Gobble
        hosts: [local, node]
        defaultEnvironment: local
        processes:
          setup:
            command: printf setup
            mode: task
          api:
            command: sleep 30
            dependsOn: [setup]
            port: 4321
          account:
            command: aws sts get-caller-identity
            mode: task
            includeInStack: false
            env:
              AWS_PROFILE: development
        environments:
          local:
            name: Local
            bindings: {setup: local, api: local}
            env: {NODE_ENV: development}
          staging:
            bindings: {api: node}
        """;
    @Test void parsesPortableDefinitionsDependenciesEnvironmentAndManualTasks(){
        var d=WorkflowYaml.parse(YAML);assertEquals(List.of("setup","api","account"),WorkflowYaml.order(d));assertEquals("local",d.environments().get("local").bindings().get("account"));assertFalse(d.processes().get("account").includeInStack());
        assertEquals(d,WorkflowYaml.parse(WorkflowYaml.dump(WorkflowManifests.map(d))));
    }
    @Test void rejectsUnknownKeysVersionsCyclesAndMalformedScalarTypes(){
        for(String invalid:List.of(YAML.replace("runtimebay.dev/v1","runtimebay.dev/v99"),YAML+"unknown: true\n",YAML.replace("dependsOn: [setup]","dependsOn: [api]"),YAML.replace("port: 4321","port: 4.5"),YAML.replace("NODE_ENV: development","NODE_ENV: true"),YAML.replace("printf setup",""),YAML.replace("directory: .","directory: /tmp"),YAML.replace("mode: task","mode: mystery"))) {
            if(invalid.equals(YAML))continue;assertEquals(400,assertThrows(ApiException.class,()->WorkflowYaml.parse(invalid)).status);
        }
        assertThrows(ApiException.class,()->WorkflowYaml.parse(YAML.replace("command: printf setup","command: printf setup\n    command: second")));
        assertThrows(ApiException.class,()->WorkflowYaml.parse("!!java/object:java.lang.ProcessBuilder {}"));
        assertThrows(ApiException.class,()->WorkflowYaml.parse(YAML+"---\nsecond: document\n"));
        assertThrows(ApiException.class,()->WorkflowYaml.parse(YAML.replace("hosts: [local, node]","hosts: &hosts [local, node]")+"x: *hosts\n"));
    }
    @Test void credentialsUseReferencesAndResolutionNeverMutatesExportedValues(){
        var text=YAML.replace("AWS_PROFILE: development","AWS_PROFILE: development\n      API_TOKEN: '${env:DEV_TOKEN}'");var d=WorkflowYaml.parse(text);
        var values=WorkflowYaml.environment(d,"local","account",name->"private-value");assertEquals("private-value",values.get("API_TOKEN"));assertFalse(WorkflowYaml.dump(WorkflowManifests.map(d)).contains("private-value"));
        assertThrows(ApiException.class,()->WorkflowYaml.environment(d,"local","account",name->null));
        assertThrows(ApiException.class,()->WorkflowYaml.parse(text.replace("'${env:DEV_TOKEN}'","inline-secret")));
        assertThrows(ApiException.class,()->WorkflowYaml.parse(text.replace("${env:DEV_TOKEN}","${env:RUNTIMEBAY_TOKEN}")));
    }
}

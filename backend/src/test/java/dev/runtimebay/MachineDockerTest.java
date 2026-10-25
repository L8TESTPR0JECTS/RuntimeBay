package dev.runtimebay;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class MachineDockerTest {
    @TempDir Path temporary;
    @Test void savedMachinesAppearWithoutCreatingContextsAndOperationsKeepTheirTarget()throws Exception{
        var hosts=new HostRegistry(temporary);var host=hosts.save(null,"Helfer","fixture.invalid","alek",22);
        var fake=new DockerServiceTest.FakeDocker();var remoteHosts=new ArrayList<String>();
        DockerService.Commands commands=new DockerService.Commands(){
            public DockerService.Result run(List<String> args,Duration timeout,Consumer<String> progress)throws java.io.IOException,InterruptedException{return fake.run(args,timeout,progress);}
            public DockerService.Result runOnHost(HostRegistry.Host selected,List<String> args,Duration timeout,Consumer<String> progress)throws java.io.IOException,InterruptedException{
                remoteHosts.add(selected.id());var forwarded=new ArrayList<>(List.of("--context","remote"));forwarded.addAll(args);return fake.run(forwarded,timeout,progress);
            }
        };
        try(var docker=new DockerService(commands)){
            docker.configureHosts(hosts::view,null,null);var discovery=docker.environments();
            var env=discovery.environments().stream().filter(e->host.name().equals(e.machineName())).findFirst().orElseThrow();assertEquals("machine-"+host.id(),env.name());
            assertTrue(fake.calls.stream().noneMatch(c->c.contains("create")));fake.calls.clear();
            var overview=docker.overview(env.name(),env.endpoint());assertEquals(host.name(),overview.environment().machineName());assertEquals(1,overview.containers().size());assertEquals(List.of(host.id(),host.id()),remoteHosts);
            assertThrows(ApiException.class,()->docker.makeDefaultEnvironment(env.name(),env.endpoint()));
            hosts.save(host.id(),"Renamed","changed.invalid","alek",22);int count=remoteHosts.size();assertEquals(409,assertThrows(ApiException.class,()->docker.overview(env.name(),env.endpoint())).status);assertEquals(count,remoteHosts.size());
            hosts.remove(host.id());assertEquals(409,assertThrows(ApiException.class,()->docker.overview(env.name(),env.endpoint())).status);
        }
    }
    @Test void hostDiscoveryWorksWithoutLocalDockerAndDoesNotConnectDuringDiscovery()throws Exception{
        var hosts=new HostRegistry(temporary);hosts.save(null,"Remote","fixture.invalid","",null);
        try(var docker=new DockerService(null)){docker.configureHosts(hosts::view,null,null);var discovery=docker.environments();assertTrue(discovery.available());assertEquals(1,discovery.environments().size());assertEquals("Remote",discovery.environments().getFirst().machineName());}
    }
}

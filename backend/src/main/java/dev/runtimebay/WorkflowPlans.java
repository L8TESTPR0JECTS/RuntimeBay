package dev.runtimebay;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Local orchestration over the existing owned-process supervisor. CLI side effects are never inferred or undone. */
final class WorkflowPlans implements AutoCloseable {
    record Step(String key,String serviceId,String name,String mode,String command,String directory,Integer port,int timeoutSeconds,List<String> dependencies,List<String> environmentKeys,boolean reuse){}
    record Preview(String revision,String environmentId,String environmentName,String target,List<Step> steps,List<String> warnings){}
    record State(String id,String state,String current,String message,List<String> completed){}
    private final ProjectStore projects;private final RuntimeManager runtimes;private final WorkflowManifests manifests;
    private final java.util.function.Supplier<HostRegistry.View> hosts;
    private final ExecutorService jobs=Executors.newVirtualThreadPerTaskExecutor();private final Map<String,Run> runs=new HashMap<>();private boolean closing;
    private static final class Run {
        final String id=UUID.randomUUID().toString();volatile String state="running",current="",message="Preparing stack…";volatile boolean cancelled;volatile Thread worker;
        final List<String> completed=new ArrayList<>();final List<String> started=new ArrayList<>();
        synchronized State view(){return new State(id,state,current,message,List.copyOf(completed));}
    }
    WorkflowPlans(ProjectStore projects,RuntimeManager runtimes,WorkflowManifests manifests,java.util.function.Supplier<HostRegistry.View> hosts){this.projects=projects;this.runtimes=runtimes;this.manifests=manifests;this.hosts=hosts;}
    synchronized State state(String id){projects.project(id);var run=runs.get(id);return run==null?new State("","idle","","No stack run in this app session",List.of()):run.view();}
    synchronized boolean active(String id){var run=runs.get(id);return run!=null&&List.of("running","cancelling").contains(run.state);}
    void requireIdle(String id){if(active(id))throw new ApiException(409,"A YAML stack plan is active. Cancel it before changing or controlling this workflow.");}
    Preview preview(String projectId,String target)throws IOException{
        manifests.requireHealthy();var entry=manifests.get(projectId);if(entry==null)throw new ApiException(409,"Preview and apply a YAML definition first");
        var graph=runtimes.workflows().get(projectId);String environment=entry.environments().entrySet().stream().filter(e->e.getValue().equals(graph.defaultEnvironmentId())).map(Map.Entry::getKey).findFirst().orElseThrow(()->new ApiException(409,"The selected environment is not declared in this YAML definition"));
        var desired=new HashSet<String>();
        if(target==null||target.isBlank()){for(var p:entry.definition().processes().entrySet())if(p.getValue().includeInStack())include(p.getKey(),entry.definition(),desired);}
        else {if(!entry.definition().processes().containsKey(target))throw new ApiException(400,"Unknown YAML process");include(target,entry.definition(),desired);}
        if(desired.isEmpty())throw new ApiException(409,"No processes are included in this stack. Select a process to run.");
        var steps=new ArrayList<Step>();var fingerprint=new StringBuilder(manifests.revision(hosts.get())).append(graph.defaultEnvironmentId()).append('\0').append(Objects.requireNonNullElse(target,""));
        for(String key:WorkflowYaml.order(entry.definition()))if(desired.contains(key)){
            var p=entry.definition().processes().get(key);String id=entry.services().get(key);var service=projects.service(projectId,id);
            if(!service.name().equals(p.name())||!service.command().equals(p.command())||!service.directory().equals(p.directory())||!Objects.equals(service.port(),p.port()))throw new ApiException(409,"Process '"+key+"' changed outside YAML. Export the current definitions and preview/apply them before running a stack.");
            runtimes.workflows().requireLocalService(projectId,id);ProjectStore.resolveDirectory(projects.project(projectId),p.directory());
            // Resolve all required env references before the first process starts. Only variable names leave this method.
            var environmentValues=WorkflowYaml.environment(entry.definition(),environment,key,System::getenv);
            var state=runtimes.processState(projectId,id);boolean active=List.of("starting","running","stopping").contains(state.state());
            if(active&&(!p.mode().equals("service")||!state.state().equals("running")))throw new ApiException(409,"Stop the active process '"+key+"' before starting this plan");
            fingerprint.append('\0').append(key).append(state);
            steps.add(new Step(key,id,p.name(),p.mode(),p.command(),p.directory(),p.port(),p.timeoutSeconds(),p.dependsOn(),environmentValues.keySet().stream().sorted().toList(),active));
        }
        return new Preview(WorkflowManifests.hash(fingerprint.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),graph.defaultEnvironmentId(),entry.definition().environments().get(environment).name(),target==null?"":target,List.copyOf(steps),List.of(
            "Run executes the listed shell commands with your current user permissions. Docker and AWS CLI tasks can change external infrastructure.",
            "Tasks must exit successfully before dependents start. Service readiness means an owned process was launched, not a health check; use an explicit readiness task when needed.",
            "Running services are reused and are not stopped on failure/cancellation. Newly launched managed processes are stopped on failure/cancellation; detached containers and cloud changes are not rolled back."));
    }
    private static void include(String key,WorkflowYaml.Definition d,Set<String> result){if(!result.add(key))return;for(String dependency:d.processes().get(key).dependsOn())include(dependency,d,result);}
    synchronized State start(String projectId,String target,String expected)throws IOException{
        if(closing)throw new ApiException(503,"Stack plans are shutting down");requireIdle(projectId);var preview=preview(projectId,target);
        if(expected==null||!expected.equals(preview.revision()))throw new ApiException(409,"The stack plan or process state changed. Review the plan again before running.");
        var run=new Run();runs.put(projectId,run);jobs.submit(()->execute(projectId,preview,run));return run.view();
    }
    private void execute(String projectId,Preview plan,Run run){
        run.worker=Thread.currentThread();var entry=manifests.get(projectId);String environment=entry.environments().entrySet().stream().filter(e->e.getValue().equals(plan.environmentId())).map(Map.Entry::getKey).findFirst().orElseThrow();
        try{
            Map<String,Map<String,String>> environmentValues=new HashMap<>();for(var step:plan.steps())environmentValues.put(step.key(),WorkflowYaml.environment(entry.definition(),environment,step.key(),System::getenv));
            for(var step:plan.steps()){
                if(run.cancelled||Thread.currentThread().isInterrupted())throw new InterruptedException();
                run.current=step.key();run.message=(step.reuse()?"Reusing ":"Running ")+step.name();
                for(String dependency:step.dependencies()){
                    var dependencyStep=plan.steps().stream().filter(s->s.key().equals(dependency)).findFirst().orElseThrow();
                    if(dependencyStep.mode().equals("service")&&!runtimes.processState(projectId,dependencyStep.serviceId()).state().equals("running"))throw new ApiException(409,"Dependency service exited before "+step.name()+" started: "+dependency);
                }
                if(!step.reuse()){
                    // Track before launch so failed or interrupted supervisor handshakes are also cleaned up.
                    run.started.add(step.serviceId());runtimes.startWithEnvironment(projectId,step.serviceId(),environmentValues.get(step.key()));
                    if(step.mode().equals("task")){
                        runtimes.awaitProcess(projectId,step.serviceId(),step.timeoutSeconds());var state=runtimes.processState(projectId,step.serviceId());
                        if(!Integer.valueOf(0).equals(state.exitCode())||!state.state().equals("exited"))throw new ApiException(409,"Task "+step.name()+" did not succeed. Inspect its service logs.");
                    }else if(!runtimes.processState(projectId,step.serviceId()).state().equals("running"))throw new ApiException(409,"Service exited during launch: "+step.name());
                }
                synchronized(run){run.completed.add(step.key());}
            }
            if(run.cancelled)throw new InterruptedException();run.message="Stack plan completed. Services keep running; task and service logs are in the runtime inspector.";run.state="completed";
        }catch(Exception error){
            boolean interrupted=error instanceof InterruptedException||run.cancelled;Thread.interrupted();
            var cleanup=new ArrayList<String>(run.started);Collections.reverse(cleanup);List<String> failures=new ArrayList<>();
            for(String id:cleanup)try{runtimes.stop(projectId,id);}catch(Exception stopError){failures.add(id);}
            run.message=(interrupted?"Plan cancelled":error instanceof TimeoutException?"Task timed out":error instanceof ApiException?error.getMessage():"Plan failed. Inspect the service logs.")+". CLI side effects are not rolled back."+(failures.isEmpty()?"":" Some processes could not be stopped; inspect their controls.");
            run.state=interrupted?"cancelled":"failed";
        }finally{run.worker=null;}
    }
    synchronized State cancel(String projectId,String expected){var run=runs.get(projectId);if(run==null||!run.id.equals(expected))throw new ApiException(409,"The active plan changed. Refresh before cancelling.");if(active(projectId)){run.cancelled=true;run.state="cancelling";run.message="Cancelling owned processes; CLI side effects remain.";var worker=run.worker;if(worker!=null)worker.interrupt();}return run.view();}
    @Override public void close(){synchronized(this){closing=true;for(var run:runs.values()){if(List.of("running","cancelling").contains(run.state)){run.cancelled=true;var worker=run.worker;if(worker!=null)worker.interrupt();}}}jobs.shutdown();try{if(!jobs.awaitTermination(8,TimeUnit.SECONDS))jobs.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();jobs.shutdownNow();}}
}

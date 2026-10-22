package dev.runtimebay;

import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import java.util.*;

/** Repository format, deliberately separate from private local registries. No YAML object construction or includes. */
final class WorkflowYaml {
    static final String VERSION = "runtimebay.dev/v1";
    record Process(String name, String command, String directory, Integer port, String mode, boolean includeInStack, int timeoutSeconds, List<String> dependsOn, Map<String,String> env) {}
    record Reference(String kind,String reference) {}
    record Environment(String name, Map<String,String> bindings, Map<String,String> resources, Map<String,String> env) {}
    record Definition(String apiVersion, String kind, String name, List<String> hosts, String defaultEnvironment, Map<String,Process> processes, Map<String,Reference> resources, Map<String,Environment> environments) {}
    static Definition parse(String text) {
        if (text == null || text.isBlank() || text.length() > 48000) throw bad("Use a nonempty YAML document of at most 48,000 characters");
        LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0); options.setNestingDepthLimit(25); options.setCodePointLimit(48000);
        Object loaded;
        try { loaded = new Yaml(new SafeConstructor(options)).load(text); }
        catch (MarkedYAMLException error) {
            var mark = error.getProblemMark();
            throw bad("Invalid YAML" + (mark == null ? "" : " at line " + (mark.getLine()+1) + ", column " + (mark.getColumn()+1)) + ". Check indentation, duplicate keys, and supported scalar types.");
        } catch (RuntimeException error) { throw bad("Invalid YAML. Use one document, without custom tags or collection aliases."); }
        var root = map(loaded, "workflow"); keys(root, "apiVersion", "kind", "name", "hosts", "defaultEnvironment", "processes", "resources", "environments");
        if (!VERSION.equals(string(root.get("apiVersion"), "apiVersion", 80))) throw bad("Unsupported apiVersion; use " + VERSION);
        if (!"Workflow".equals(string(root.get("kind"), "kind", 80))) throw bad("kind must be Workflow");
        String name = string(root.get("name"), "name", 80);
        List<String> hosts = strings(root.getOrDefault("hosts", List.of("local")), "hosts", 100);
        if (!hosts.contains("local") || new HashSet<>(hosts).size()!=hosts.size()) throw bad("hosts must contain local and unique logical aliases");
        for (String host : hosts) key(host);
        var definitions = map(root.getOrDefault("processes",Map.of()), "processes");
        if (definitions.size()>100) throw bad("Define at most 100 processes");
        var processes = new LinkedHashMap<String,Process>(); Set<String> names = new HashSet<>();
        for (var entry : definitions.entrySet()) {
            String id = entry.getKey(); key(id); var p = map(entry.getValue(), "process " + id);
            keys(p,"name","command","directory","port","mode","timeoutSeconds","dependsOn","env","includeInStack");
            String label=string(p.getOrDefault("name",id),"process name",80);
            if (!names.add(label.toLowerCase(Locale.ROOT))) throw bad("Process names must be unique");
            String command=string(p.get("command"),"command for "+id,4096);
            String directory=string(p.getOrDefault("directory","."),"directory",512);
            if (java.nio.file.Path.of(directory).isAbsolute()) throw bad("Process directories must be relative to the imported project");
            String mode=string(p.getOrDefault("mode","service"),"mode",20);
            if (!List.of("task","service").contains(mode)) throw bad("mode must be task or service");
            Object included=p.getOrDefault("includeInStack",true);if(!(included instanceof Boolean))throw bad("includeInStack must be true or false");
            Integer port=p.containsKey("port") ? number(p.get("port"),"port",1,65535) : null;
            int timeout=number(p.getOrDefault("timeoutSeconds",300),"timeoutSeconds",1,3600);
            var dependencies=strings(p.getOrDefault("dependsOn",List.of()),"dependsOn",100);
            if (new HashSet<>(dependencies).size()!=dependencies.size()) throw bad("Duplicate dependency in "+id);
            processes.put(id,new Process(label,command,directory,port,mode,(Boolean)included,timeout,dependencies,env(p.getOrDefault("env",Map.of()))));
        }
        for (var p:processes.values()) for(String dependency:p.dependsOn()) if (!processes.containsKey(dependency)) throw bad("Unknown process dependency: "+dependency);
        var resources=new LinkedHashMap<String,Reference>();
        var declaredResources=map(root.getOrDefault("resources",Map.of()),"resources");if(declaredResources.size()>100)throw bad("Too many resource references");
        for(var item:declaredResources.entrySet()){key(item.getKey());var r=map(item.getValue(),"resource "+item.getKey());keys(r,"kind","reference");String kind=string(r.get("kind"),"resource kind",40);if(!List.of("service","container","database","terminal").contains(kind))throw bad("Resource kinds are service, container, database, or terminal");String alias=string(r.getOrDefault("reference",item.getKey()),"resource reference",80);key(alias);resources.put(item.getKey(),new Reference(kind,alias));}
        if(processes.isEmpty()&&resources.isEmpty())throw bad("Define at least one process or resource");
        var environments = new LinkedHashMap<String,Environment>(); var definitionsEnv=map(root.get("environments"),"environments");
        if (definitionsEnv.isEmpty() || definitionsEnv.size()>20) throw bad("Define between 1 and 20 environments");
        Set<String> environmentNames=new HashSet<>();
        for(var entry:definitionsEnv.entrySet()) {
            String id=entry.getKey();key(id); 
            var e=map(entry.getValue(),"environment "+id);keys(e,"name","bindings","resources","env");
            String label=string(e.getOrDefault("name",id),"environment name",128);if(!environmentNames.add(label.toLowerCase(Locale.ROOT)))throw bad("Environment names must be unique ignoring case");
            var bindings=new LinkedHashMap<String,String>();var raw=map(e.getOrDefault("bindings",Map.of()),"bindings");
            for(var binding:raw.entrySet()) {
                if(!processes.containsKey(binding.getKey())) throw bad("Binding names an unknown process: "+binding.getKey());
                String alias=string(binding.getValue(),"host alias",80);if(!hosts.contains(alias)) throw bad("Unknown logical host alias: "+alias);
                bindings.put(binding.getKey(),alias);
            }
            for(String process:processes.keySet()) bindings.putIfAbsent(process,"local");
            var references=new LinkedHashMap<String,String>();var selectedResources=map(e.getOrDefault("resources",Map.of()),"environment resources");
            for(var item:selectedResources.entrySet()){if(!resources.containsKey(item.getKey()))throw bad("Unknown resource role: "+item.getKey());if(item.getValue()==null){if(resources.get(item.getKey()).kind().equals("container"))throw bad("Container roles need a reference in every environment; their association follows the existing container-link model");references.put(item.getKey(),null);}else{String alias=string(item.getValue(),"resource reference",80);key(alias);references.put(item.getKey(),alias);}}
            for(var item:resources.entrySet())references.putIfAbsent(item.getKey(),item.getValue().reference());
            // putIfAbsent also fills nulls; restore explicit disabled external references.
            for(var item:selectedResources.entrySet())if(item.getValue()==null)references.put(item.getKey(),null);
            environments.put(id,new Environment(label,Collections.unmodifiableMap(bindings),Collections.unmodifiableMap(references),env(e.getOrDefault("env",Map.of()))));
        }
        String selected=string(root.getOrDefault("defaultEnvironment",environments.keySet().iterator().next()),"defaultEnvironment",80);
        if(!environments.containsKey(selected)) throw bad("defaultEnvironment must name a declared environment");
        var definition=new Definition(VERSION,"Workflow",name,List.copyOf(hosts),selected,Collections.unmodifiableMap(processes),Collections.unmodifiableMap(resources),Collections.unmodifiableMap(environments)); order(definition);referenceKinds(definition);return definition;
    }
    static Map<String,String> referenceKinds(Definition definition){
        var result=new LinkedHashMap<String,String>();for(var e:definition.environments().values())for(var r:e.resources().entrySet())if(r.getValue()!=null){String kind=definition.resources().get(r.getKey()).kind();String previous=result.putIfAbsent(r.getValue(),kind);if(previous!=null&&!previous.equals(kind))throw bad("A reference alias cannot have multiple kinds: "+r.getValue());}return Collections.unmodifiableMap(result);
    }
    static List<String> order(Definition definition) {
        var result=new ArrayList<String>();var visiting=new HashSet<String>();var done=new HashSet<String>();
        for(String id:definition.processes().keySet()) visit(id,definition,visiting,done,result);
        return List.copyOf(result);
    }
    private static void visit(String id,Definition definition,Set<String> visiting,Set<String> done,List<String> result) {
        if(done.contains(id))return;if(!visiting.add(id))throw bad("Process dependency cycle at "+id);
        for(String dep:definition.processes().get(id).dependsOn())visit(dep,definition,visiting,done,result);
        visiting.remove(id);done.add(id);result.add(id);
    }
    static Map<String,String> environment(Definition definition,String environment,String process,java.util.function.Function<String,String> lookup) {
        var values=new LinkedHashMap<>(definition.environments().get(environment).env()); values.putAll(definition.processes().get(process).env());
        var resolved=new LinkedHashMap<String,String>();
        for(var e:values.entrySet()) {
            String value=e.getValue();
            if(value.startsWith("${env:") && value.endsWith("}")) { String name=value.substring(6,value.length()-1);value=lookup.apply(name);if(value==null)throw new ApiException(409,"Required environment reference is unavailable: "+name); }
            if(value.indexOf('\0')>=0)throw bad("Environment value contains a null character");resolved.put(e.getKey(),value);
        }
        return Map.copyOf(resolved);
    }
    static String dump(Map<String,Object> value) {
        DumperOptions options=new DumperOptions();options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);options.setPrettyFlow(true);options.setIndent(2);
        return new Yaml(options).dump(copyTree(value));
    }
    private static Object copyTree(Object value){if(value instanceof Map<?,?> map){var copy=new LinkedHashMap<Object,Object>();for(var e:map.entrySet())copy.put(e.getKey(),copyTree(e.getValue()));return copy;}if(value instanceof List<?> list){var copy=new ArrayList<Object>();for(var v:list)copy.add(copyTree(v));return copy;}return value;}
    private static Map<String,String> env(Object value) {
        var result=new LinkedHashMap<String,String>();var values=map(value,"env");if(values.size()>100)throw bad("Too many environment variables");
        for(var entry:values.entrySet()) {
            String key=entry.getKey();if(!key.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || key.startsWith("RUNTIMEBAY_"))throw bad("Invalid or reserved environment variable: "+key);
            String v=string(entry.getValue(),"environment value for "+key,4096);
            if(v.startsWith("${env:")) {
                if(!v.matches("\\$\\{env:[A-Za-z_][A-Za-z0-9_]{0,127}}") || v.substring(6).startsWith("RUNTIMEBAY_"))throw bad("Use a complete ${env:VARIABLE} reference to a non-reserved variable");
            } else if(key.matches("(?i).*(PASSWORD|SECRET|TOKEN|ACCESS_KEY|PRIVATE_KEY|API_KEY).*") )throw bad("Use ${env:VARIABLE} instead of an inline credential for "+key);
            result.put(key,v);
        }
        return Collections.unmodifiableMap(result);
    }
    private static Map<String,Object> map(Object value,String label) {
        if(!(value instanceof Map<?,?> raw))throw bad(label+" must be a mapping");var result=new LinkedHashMap<String,Object>();
        for(var e:raw.entrySet()) {if(!(e.getKey() instanceof String key))throw bad(label+" keys must be strings"); result.put(key,e.getValue());}return result;
    }
    private static void keys(Map<String,Object> map,String...allowed) {var valid=Set.of(allowed);for(String key:map.keySet())if(!valid.contains(key))throw bad("Unsupported configuration key: "+key);}
    private static List<String> strings(Object value,String label,int max) {if(!(value instanceof List<?> list)||list.size()>max)throw bad(label+" must be a bounded list");var values=new ArrayList<String>();for(Object item:list)values.add(string(item,label,80));return List.copyOf(values);}
    private static String string(Object value,String label,int max) {if(!(value instanceof String s)||s.isBlank()||s.length()>max||s.indexOf('\0')>=0)throw bad(label+" must be a nonempty string (quote numeric/boolean YAML values)");return s;}
    private static int number(Object value,String label,int min,int max) {if(!(value instanceof Integer n)||n<min||n>max)throw bad(label+" must be a whole number from "+min+" to "+max);return (Integer)value;}
    private static void key(String key) {if(!key.matches("[A-Za-z][A-Za-z0-9_-]{0,79}"))throw bad("Logical names use letters, numbers, dashes and underscores: "+key);}
    private static ApiException bad(String message) {return new ApiException(400,message);}
    private WorkflowYaml(){}
}

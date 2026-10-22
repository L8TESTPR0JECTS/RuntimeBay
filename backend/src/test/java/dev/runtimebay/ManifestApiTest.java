package dev.runtimebay;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManifestApiTest {
    @TempDir Path temporary; ProjectStore projects; RuntimeManager runtimes; ApiServer server; DockerService docker; String project;
    final String token="y".repeat(64);final Gson gson=new Gson();final HttpClient client=HttpClient.newHttpClient();
    @BeforeEach void setup()throws Exception{
        projects=new ProjectStore(temporary.resolve("data"));project=projects.addProject("Fixture",temporary.toString()).id();runtimes=new RuntimeManager(projects,Path.of("/must-not-execute"));docker=new DockerService(null);
        Path web=Files.createDirectory(temporary.resolve("web"));Files.writeString(web.resolve("index.html"),"fixture");server=new ApiServer(projects,runtimes,web,token,docker);server.start();
    }
    @AfterEach void close(){server.close();runtimes.close();docker.close();}
    HttpResponse<String> call(String suffix,String method,Object body)throws Exception{
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/api/"+suffix)).header("Authorization","Bearer "+token).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void authenticatedReviewImportAndFileSaveNeverExecuteAndStaleRevisionsAreRejected()throws Exception{
        String path="workflows/"+project+"/manifest";String yaml=WorkflowPlansTest.YAML;
        assertEquals(401,client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/api/"+path)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        var preview=call(path+"/preview","POST",Map.of("yaml",yaml));assertEquals(200,preview.statusCode(),preview.body());String revision=JsonParser.parseString(preview.body()).getAsJsonObject().get("revision").getAsString();
        assertEquals(409,call(path+"/apply","POST",Map.of("yaml",yaml,"revision","stale")).statusCode());assertTrue(projects.project(project).services().isEmpty());
        var applied=call(path+"/apply","POST",Map.of("yaml",yaml,"revision",revision));assertEquals(200,applied.statusCode(),applied.body());assertFalse(Files.exists(temporary.resolve("prepared")));
        assertEquals(200,call(path+"/save","POST",Map.of("yaml",yaml,"expectedFileHash","missing")).statusCode());assertEquals(yaml,Files.readString(temporary.resolve("runtimebay.yaml")));
        assertEquals(409,call(path+"/save","POST",Map.of("yaml",yaml,"expectedFileHash","missing")).statusCode());
        String service=projects.project(project).services().getFirst().id();assertEquals(409,call("projects/"+project+"/services/"+service+"/start","POST",Map.of()).statusCode());
        assertEquals(409,call(path+"/run","POST",Map.of("revision","stale")).statusCode());assertFalse(Files.exists(temporary.resolve("prepared")));assertNull(runtimes.processState(project,service).startedAt());
    }
}

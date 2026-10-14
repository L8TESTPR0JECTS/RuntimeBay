package dev.runtimebay;

import java.nio.file.*;
import java.util.concurrent.CountDownLatch;

public final class Main {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(setting("RUNTIMEBAY_DATA_DIR", System.getProperty("user.home") + "/Library/Application Support/Runtime Bay"));
        Path launcher = Path.of(required("RUNTIMEBAY_LAUNCHER")).toRealPath();
        if (!Files.isExecutable(launcher)) throw new IllegalArgumentException("Service supervisor is not executable");
        ProjectStore store = new ProjectStore(data);
        RuntimeManager runtimes = new RuntimeManager(store, launcher);
        DockerService docker = DockerService.discover();
        ApiServer server = new ApiServer(store, runtimes, Path.of(required("RUNTIMEBAY_WEB_ROOT")), required("RUNTIMEBAY_TOKEN"), docker);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close(); docker.close(); runtimes.close();
        }, "runtime-bay-shutdown"));
        server.start();
        System.out.println("RUNTIME_BAY_READY:" + server.port());
        System.out.flush();
        new CountDownLatch(1).await();
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
    private static String setting(String name, String fallback) {
        String value = System.getenv(name); return value == null || value.isBlank() ? fallback : value;
    }
}

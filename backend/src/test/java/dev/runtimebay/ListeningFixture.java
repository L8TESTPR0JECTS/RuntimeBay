package dev.runtimebay;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;

public class ListeningFixture {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 1);
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.start();
        System.out.println("ready");
        new CountDownLatch(1).await();
    }
}

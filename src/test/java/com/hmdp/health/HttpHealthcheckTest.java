package com.hmdp.health;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpHealthcheckTest {
    @Test
    void acceptsOnlySuccessfulHttpResponses() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ready", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/unavailable", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            assertTrue(HttpHealthcheck.isHealthy(baseUrl + "/ready"));
            assertFalse(HttpHealthcheck.isHealthy(baseUrl + "/unavailable"));
            assertFalse(HttpHealthcheck.isHealthy(baseUrl + "/missing"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsUnhealthyWhenTargetIsUnavailable() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.start();
        server.stop(0);

        assertFalse(HttpHealthcheck.isHealthy("http://127.0.0.1:" + port + "/actuator/health"));
    }
}

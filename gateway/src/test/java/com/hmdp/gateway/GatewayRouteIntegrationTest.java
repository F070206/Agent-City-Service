package com.hmdp.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "GATEWAY_TEST_REDIS_HOST", matches = ".+")
class GatewayRouteIntegrationTest {
    private static HttpServer firstBackend;
    private static HttpServer secondBackend;
    private static ConfigurableApplicationContext secondGateway;

    @Value("${local.server.port}")
    private int gatewayPort;

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry properties) {
        firstBackend = backend("first");
        secondBackend = backend("second");
        properties.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        properties.add("spring.cloud.nacos.discovery.register-enabled", () -> "false");
        properties.add("management.endpoint.health.group.readiness.include", () -> "readinessState,redis");
        properties.add("spring.cloud.discovery.client.simple.instances.cityai-content[0].uri",
                () -> "http://127.0.0.1:" + firstBackend.getAddress().getPort());
        properties.add("spring.cloud.discovery.client.simple.instances.cityai-content[1].uri",
                () -> "http://127.0.0.1:" + secondBackend.getAddress().getPort());
        properties.add("spring.cloud.discovery.client.simple.instances.cityai-user[0].uri",
                () -> "http://127.0.0.1:" + firstBackend.getAddress().getPort());
        properties.add("spring.cloud.discovery.client.simple.instances.cityai-order[0].uri",
                () -> "http://127.0.0.1:" + firstBackend.getAddress().getPort());
        properties.add("spring.cloud.discovery.client.simple.instances.cityai-ai[0].uri",
                () -> "http://127.0.0.1:" + firstBackend.getAddress().getPort());
        properties.add("spring.data.redis.host", () -> System.getenv("GATEWAY_TEST_REDIS_HOST"));
        properties.add("spring.data.redis.port",
                () -> System.getenv().getOrDefault("GATEWAY_TEST_REDIS_PORT", "6379"));
        properties.add("spring.data.redis.database",
                () -> System.getenv().getOrDefault("GATEWAY_TEST_REDIS_DATABASE", "0"));
        properties.add("GATEWAY_AUTH_BURST", () -> "1");
    }

    @AfterAll
    static void stopBackends() {
        if (secondGateway != null) {
            secondGateway.close();
        }
        if (firstBackend != null) {
            firstBackend.stop(0);
        }
        if (secondBackend != null) {
            secondBackend.stop(0);
        }
    }

    @Test
    void contentRouteBalancesInstancesAndInternalPathStaysPrivate() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        Set<String> backends = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + gatewayPort + "/shop/1?probe=" + i)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            backends.add(response.body());
        }
        assertEquals(Set.of("first:/shop/1", "second:/shop/1"), backends);

        HttpResponse<String> internal = client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + gatewayPort + "/internal/users/1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, internal.statusCode());
        for (String path : new String[]{"/user/../internal/users/1", "/user/%2e%2e/internal/users/1",
                "/user/%2e./internal/users/1", "/user/%2e%2e%2finternal/users/1"}) {
            HttpResponse<String> variant = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + gatewayPort + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertNotEquals(200, variant.statusCode(), path);
        }
    }

    @Test
    void userOrderAndAiRoutesReachTheirServices() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        for (String path : new String[]{"/user/me", "/payment/status/1", "/ai/agent/recommend"}) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + gatewayPort + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), path);
            assertEquals("first:" + path, response.body(), path);
        }

        HttpResponse<String> stream = client.send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + gatewayPort + "/ai/agent/conversations/1/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, stream.statusCode());
        assertTrue(stream.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"));
        assertTrue(stream.body().contains("event: answer_delta"));
    }

    @Test
    void authRoutesShareRedisRateLimit() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String path = i % 2 == 0 ? "/user/code" : "/user/login";
            requests.add(client.sendAsync(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + gatewayPort + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
        int allowed = 0;
        int rejected = 0;
        for (CompletableFuture<HttpResponse<String>> request : requests) {
            int status = request.get().statusCode();
            allowed += status == 200 ? 1 : 0;
            rejected += status == 429 ? 1 : 0;
            assertTrue(status == 200 || status == 429, "unexpected status: " + status);
        }
        assertTrue(allowed > 0);
        assertTrue(rejected > 0);
    }

    @Test
    void authRateLimitRefillsAfterSustainedRequests() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://127.0.0.1:" + gatewayPort + "/user/code");
        Thread.sleep(1500);
        int first = client.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
        int second = client.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
        assertEquals(200, first);
        assertEquals(429, second);
        Thread.sleep(1500);
        int refilled = client.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
        assertEquals(200, refilled);
    }

    @Test
    void separateGatewayInstancesShareRedisRateLimit() throws Exception {
        secondGateway = new SpringApplicationBuilder(GatewayApplication.class)
                .run("--server.port=0",
                        "--spring.cloud.nacos.discovery.enabled=false",
                        "--spring.cloud.nacos.discovery.register-enabled=false",
                        "--management.endpoint.health.group.readiness.include=readinessState,redis",
                        "--spring.cloud.discovery.client.simple.instances.cityai-user[0].uri=http://127.0.0.1:"
                                + firstBackend.getAddress().getPort(),
                        "--spring.data.redis.host=" + System.getenv("GATEWAY_TEST_REDIS_HOST"),
                        "--spring.data.redis.port=" + System.getenv().getOrDefault("GATEWAY_TEST_REDIS_PORT", "6379"),
                        "--spring.data.redis.database="
                                + System.getenv().getOrDefault("GATEWAY_TEST_REDIS_DATABASE", "0"),
                        "--GATEWAY_AUTH_BURST=1");
        int secondPort = ((ReactiveWebServerApplicationContext) secondGateway).getWebServer().getPort();
        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<HttpResponse<String>> first = client.sendAsync(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + gatewayPort + "/user/code")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        CompletableFuture<HttpResponse<String>> second = client.sendAsync(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + secondPort + "/user/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(Set.of(200, 429), Set.of(first.get().statusCode(), second.get().statusCode()));
    }

    private static HttpServer backend(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                boolean stream = exchange.getRequestURI().getPath().endsWith("/stream");
                if (stream) {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                }
                byte[] body = (stream ? "event: answer_delta\ndata: test\n\n"
                        : name + ":" + exchange.getRequestURI().getPath())
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

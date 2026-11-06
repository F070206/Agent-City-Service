package com.hmdp.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiAgentProperties;
import com.hmdp.ai.dto.RouteAdviceDTO;
import com.hmdp.ai.dto.WeatherDiningAdviceDTO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LocalLifeMcpSseTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CountDownLatch closeSse = new CountDownLatch(1);
    private final AtomicReference<OutputStream> sse = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger handshakes = new AtomicInteger();
    private HttpServer server;
    private ExecutorService executor;
    private LocalLifeMcpService service;

    @AfterEach
    void cleanUp() {
        if (service != null) {
            service.close();
        }
        closeSse.countDown();
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void routeAndWeatherToolsUseMcpHandshakeAndToolCall() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/sse", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream stream = exchange.getResponseBody();
            sse.set(stream);
            stream.write("event: endpoint\ndata: /message\n\n".getBytes(StandardCharsets.UTF_8));
            stream.flush();
            try {
                closeSse.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/message", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            if (!request.has("id")) {
                return;
            }
            String method = request.path("method").asText();
            String result;
            if ("initialize".equals(method)) {
                handshakes.incrementAndGet();
                result = "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{}},"
                        + "\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}}";
            } else if ("tools/call".equals(method)) {
                calls.incrementAndGet();
                String tool = request.path("params").path("name").asText();
                String payload;
                if ("route_plan".equals(tool)) {
                    assertEquals("店铺", request.path("params").path("arguments").path("destinationName").asText());
                    payload = calls.get() == 1 ? null
                            : "{\"destinationName\":\"店铺\",\"source\":\"mcp\",\"routeSummary\":\"地铁两站\"}";
                } else {
                    assertEquals("weather_brief", tool);
                    assertEquals("上海", request.path("params").path("arguments").path("city").asText());
                    payload = "{\"city\":\"上海\",\"source\":\"mcp\",\"weatherSummary\":\"晴，20°C\"}";
                }
                result = payload == null ? "{\"isError\":true,\"content\":[]}"
                        : "{\"content\":[{\"type\":\"text\",\"text\":"
                        + json.writeValueAsString(payload) + "}]}";
            } else {
                return;
            }
            OutputStream stream = sse.get();
            synchronized (stream) {
                stream.write(("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":"
                        + request.get("id") + ",\"result\":" + result + "}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                stream.flush();
            }
        });
        server.start();

        AiAgentProperties properties = new AiAgentProperties();
        properties.getMcp().setEnabled(true);
        properties.getMcp().setTimeoutMillis(3000);
        properties.getMcp().getRoute().setEnabled(true);
        properties.getMcp().getRoute().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getMcp().getWeather().setEnabled(true);
        properties.getMcp().getWeather().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        service = new LocalLifeMcpService(properties, json);

        RouteAdviceDTO fallback = service.routeAdvice("店铺", "地址", null, null, null, null);
        assertTrue(fallback.isDegraded());
        RouteAdviceDTO advice = service.routeAdvice("店铺", "地址", null, null, null, null);
        assertEquals("地铁两站", advice.getRouteSummary());
        assertFalse(advice.isDegraded());
        WeatherDiningAdviceDTO weather = service.weatherAdvice("上海", "午餐");
        assertEquals("晴，20°C", weather.getWeatherSummary());
        assertFalse(weather.isDegraded());
        assertEquals(3, calls.get());
        assertEquals(3, handshakes.get());
    }
}

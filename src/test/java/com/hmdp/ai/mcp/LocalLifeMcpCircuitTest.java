package com.hmdp.ai.mcp;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiAgentProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalLifeMcpCircuitTest {
    private HttpServer server;

    @AfterEach
    void cleanUp() {
        DegradeRuleManager.loadRules(List.of());
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void openCircuitSkipsRemoteAndUsesRouteFallback() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tools/invoke", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        AiAgentProperties properties = new AiAgentProperties();
        properties.getMcp().setEnabled(true);
        properties.getMcp().getRoute().setEnabled(true);
        properties.getMcp().getRoute().setTransport("legacy-http");
        properties.getMcp().getRoute().setInvokePath("/tools/invoke");
        properties.getMcp().getRoute().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        DegradeRuleManager.loadRules(List.of(new DegradeRule("ai:mcp:route")
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.5).setMinRequestAmount(1).setStatIntervalMs(1000).setTimeWindow(10)));
        LocalLifeMcpService service = new LocalLifeMcpService(properties, new ObjectMapper());

        assertTrue(service.routeAdvice("店铺", "地址", null, null, null, null).isDegraded());
        assertTrue(service.routeAdvice("店铺", "地址", null, null, null, null).isDegraded());
        assertEquals(1, requests.get());
    }

    @Test
    void emptySuccessfulRemoteResultsUseLocalFallback() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tools/invoke", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        AiAgentProperties properties = new AiAgentProperties();
        properties.getMcp().setEnabled(true);
        properties.getMcp().getRoute().setEnabled(true);
        properties.getMcp().getRoute().setTransport("legacy-http");
        properties.getMcp().getRoute().setInvokePath("/tools/invoke");
        properties.getMcp().getRoute().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getMcp().getWeather().setEnabled(true);
        properties.getMcp().getWeather().setTransport("legacy-http");
        properties.getMcp().getWeather().setInvokePath("/tools/invoke");
        properties.getMcp().getWeather().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        LocalLifeMcpService service = new LocalLifeMcpService(properties, new ObjectMapper());

        var routeAdvice = service.routeAdvice("店铺", "地址", null, null, null, null);
        var weatherAdvice = service.weatherAdvice("上海", "午餐");

        assertTrue(routeAdvice.isDegraded());
        assertEquals("local-fallback", routeAdvice.getSource());
        assertTrue(weatherAdvice.isDegraded());
        assertEquals("local-fallback", weatherAdvice.getSource());
        assertEquals(2, requests.get());
        service.close();
    }

    @Test
    void timeoutUsesExplicitLocalFallback() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tools/invoke", exchange -> {
            requests.incrementAndGet();
            try {
                TimeUnit.SECONDS.sleep(2);
                byte[] body = "{\"data\":{\"routeSummary\":\"迟到路线\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        AiAgentProperties properties = new AiAgentProperties();
        properties.getMcp().setEnabled(true);
        properties.getMcp().setTimeoutMillis(500);
        properties.getMcp().getRoute().setEnabled(true);
        properties.getMcp().getRoute().setTransport("legacy-http");
        properties.getMcp().getRoute().setInvokePath("/tools/invoke");
        properties.getMcp().getRoute().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        LocalLifeMcpService service = new LocalLifeMcpService(properties, new ObjectMapper());

        var fallback = service.routeAdvice("店铺", "地址", null, null, null, null);

        assertTrue(fallback.isDegraded());
        assertEquals("local-fallback", fallback.getSource());
        assertEquals(1, requests.get());
        service.close();
    }

    @Test
    void interruptedRemoteCallPreservesThreadInterruptInsteadOfUsingFallback() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tools/invoke", exchange -> {
            requestReceived.countDown();
            try {
                releaseResponse.await(3, TimeUnit.SECONDS);
                byte[] body = "{\"data\":{\"routeSummary\":\"迟到路线\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        AiAgentProperties properties = new AiAgentProperties();
        properties.getMcp().setEnabled(true);
        properties.getMcp().getRoute().setEnabled(true);
        properties.getMcp().getRoute().setTransport("legacy-http");
        properties.getMcp().getRoute().setInvokePath("/tools/invoke");
        properties.getMcp().getRoute().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        LocalLifeMcpService service = new LocalLifeMcpService(properties, new ObjectMapper());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> invocationThread = new AtomicReference<>();
        try {
            Future<Boolean> interrupted = executor.submit(() -> {
                invocationThread.set(Thread.currentThread());
                try {
                    service.routeAdvice("店铺", "地址", null, null, null, null);
                    return false;
                } catch (IllegalStateException e) {
                    return Thread.currentThread().isInterrupted();
                }
            });

            assertTrue(requestReceived.await(3, TimeUnit.SECONDS));
            invocationThread.get().interrupt();
            assertTrue(interrupted.get(3, TimeUnit.SECONDS));
        } finally {
            releaseResponse.countDown();
            executor.shutdownNow();
            service.close();
        }
    }

    @Test
    void toolOutsideWhitelistNeverCallsRemoteServer() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tools/invoke", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        AiAgentProperties properties = new AiAgentProperties();
        properties.getMcp().setEnabled(true);
        properties.getMcp().setToolWhitelist(List.of("allowed_tool"));
        properties.getMcp().getRoute().setEnabled(true);
        properties.getMcp().getRoute().setToolName("unlisted_tool");
        properties.getMcp().getRoute().setTransport("legacy-http");
        properties.getMcp().getRoute().setInvokePath("/tools/invoke");
        properties.getMcp().getRoute().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        LocalLifeMcpService service = new LocalLifeMcpService(properties, new ObjectMapper());

        var fallback = service.routeAdvice("店铺", "地址", null, null, null, null);

        assertTrue(fallback.isDegraded());
        assertEquals("local-fallback", fallback.getSource());
        assertEquals(0, requests.get());
        service.close();
    }

    @Test
    void recoveredRemoteReplacesLocalFallbackAfterCircuitWindow() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tools/invoke", exchange -> {
            int request = requests.incrementAndGet();
            if (request == 1) {
                exchange.sendResponseHeaders(500, -1);
            } else {
                byte[] body = "{\"data\":{\"destinationName\":\"店铺\",\"source\":\"mcp\","
                        .concat("\"routeSummary\":\"恢复路线\"}}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        AiAgentProperties properties = new AiAgentProperties();
        properties.getMcp().setEnabled(true);
        properties.getMcp().getRoute().setEnabled(true);
        properties.getMcp().getRoute().setTransport("legacy-http");
        properties.getMcp().getRoute().setInvokePath("/tools/invoke");
        properties.getMcp().getRoute().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        DegradeRuleManager.loadRules(List.of(new DegradeRule("ai:mcp:route")
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.5).setMinRequestAmount(1).setStatIntervalMs(1000).setTimeWindow(1)));
        LocalLifeMcpService service = new LocalLifeMcpService(properties, new ObjectMapper());

        assertTrue(service.routeAdvice("店铺", "地址", null, null, null, null).isDegraded());
        assertTrue(service.routeAdvice("店铺", "地址", null, null, null, null).isDegraded());
        assertEquals(1, requests.get());
        Thread.sleep(1200);
        var recovered = service.routeAdvice("店铺", "地址", null, null, null, null);
        assertFalse(recovered.isDegraded());
        assertEquals("恢复路线", recovered.getRouteSummary());
        assertEquals(2, requests.get());
    }
}

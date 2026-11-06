package com.hmdp.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayReadinessHealthTest {
    @Test
    void readinessTracksRedisHealthOnManagementPort() throws Exception {
        ReadinessApplication.serverPortsStarted = new CountDownLatch(2);
        ReadinessApplication.applicationReady = new CountDownLatch(1);
        ReadinessApplication.redisHealthy = new AtomicBoolean(false);
        ReadinessApplication.nacosHealthy = new AtomicBoolean(false);
        ReadinessApplication.servicePort = new AtomicInteger();
        ReadinessApplication.managementPort = new AtomicInteger();
        AtomicReference<ConfigurableApplicationContext> context = new AtomicReference<>();
        Thread starter = new Thread(() -> context.set(new SpringApplication(ReadinessApplication.class).run(
                "--server.port=0", "--management.server.port=0",
                "--management.endpoints.web.exposure.include=health",
                "--spring.cloud.gateway.enabled=false",
                "--spring.cloud.nacos.discovery.enabled=false",
                "--spring.main.banner-mode=off")), "gateway-readiness-test");
        starter.setDaemon(true);
        starter.start();

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            assertTrue(ReadinessApplication.serverPortsStarted.await(15, TimeUnit.SECONDS));
            assertTrue(ReadinessApplication.applicationReady.await(15, TimeUnit.SECONDS));
            assertEquals(503, get(client, ReadinessApplication.managementPort.get(),
                    "/actuator/health/readiness"));

            ReadinessApplication.redisHealthy.set(true);
            Thread.sleep(100);
            assertEquals(503, get(client, ReadinessApplication.managementPort.get(),
                    "/actuator/health/readiness"));

            ReadinessApplication.nacosHealthy.set(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            int status = 0;
            while (System.nanoTime() < deadline && status != 200) {
                status = get(client, ReadinessApplication.managementPort.get(),
                        "/actuator/health/readiness");
                if (status != 200) {
                    Thread.sleep(50);
                }
            }
            assertEquals(200, status);
            assertEquals(404, get(client, ReadinessApplication.servicePort.get(),
                    "/actuator/health/readiness"));
        } finally {
            ConfigurableApplicationContext started = context.get();
            if (started != null) {
                started.close();
            }
            starter.join(TimeUnit.SECONDS.toMillis(10));
        }
    }

    private int get(HttpClient client, int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(2)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
    })
    static class ReadinessApplication {
        private static CountDownLatch serverPortsStarted;
        private static CountDownLatch applicationReady;
        private static AtomicBoolean redisHealthy;
        private static AtomicBoolean nacosHealthy;
        private static AtomicInteger servicePort;
        private static AtomicInteger managementPort;

        @Bean
        HealthIndicator redis() {
            return () -> redisHealthy.get() ? Health.up().build() : Health.down().build();
        }

        @Bean
        HealthIndicator nacosDiscovery() {
            return () -> nacosHealthy.get() ? Health.up().build() : Health.down().build();
        }

        @Bean
        ApplicationListener<WebServerInitializedEvent> webServerListener() {
            return event -> {
                if (event.getApplicationContext().getServerNamespace() == null) {
                    servicePort.set(event.getWebServer().getPort());
                } else {
                    managementPort.set(event.getWebServer().getPort());
                }
                serverPortsStarted.countDown();
            };
        }

        @Bean
        ApplicationListener<ApplicationReadyEvent> applicationReadyListener() {
            return event -> applicationReady.countDown();
        }
    }
}

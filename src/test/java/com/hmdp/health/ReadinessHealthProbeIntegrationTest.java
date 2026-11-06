package com.hmdp.health;

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

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
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

class ReadinessHealthProbeIntegrationTest {
    @Test
    void readinessWaitsForStartupAndCriticalDependenciesOnManagementPort() throws Exception {
        ProbeApplication.serverStarted = new CountDownLatch(1);
        ProbeApplication.managementServerStarted = new CountDownLatch(1);
        ProbeApplication.releaseRunner = new CountDownLatch(1);
        ProbeApplication.servicePort = new AtomicInteger();
        ProbeApplication.managementPort = new AtomicInteger();
        ProbeApplication.dependenciesHealthy = new AtomicBoolean(false);
        AtomicReference<ConfigurableApplicationContext> context = new AtomicReference<>();
        SpringApplication app = new SpringApplication(ProbeApplication.class);
        String exclusions = String.join(",",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
                "org.redisson.spring.starter.RedissonAutoConfigurationV2",
                "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
                "org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration",
                "com.alibaba.cloud.nacos.NacosDiscoveryAutoConfiguration",
                "com.alibaba.cloud.nacos.endpoint.NacosDiscoveryEndpointAutoConfiguration",
                "com.alibaba.cloud.nacos.registry.NacosServiceRegistryAutoConfiguration",
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
                "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
                "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
                "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
                "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration",
                "org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration",
                "org.springframework.cloud.client.serviceregistry.AutoServiceRegistrationAutoConfiguration");
        Thread starter = new Thread(() -> context.set(app.run(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.profiles.active=micro",
                "--spring.autoconfigure.exclude=" + exclusions,
                "--spring.cloud.discovery.enabled=false",
                "--spring.cloud.nacos.discovery.enabled=false",
                "--management.endpoints.web.exposure.include=health",
                "--management.endpoint.health.probes.enabled=true",
                "--spring.main.banner-mode=off")), "readiness-probe-test-app");
        starter.setDaemon(true);
        starter.start();

        try {
            assertTrue(ProbeApplication.serverStarted.await(10, TimeUnit.SECONDS));
            assertTrue(ProbeApplication.managementServerStarted.await(10, TimeUnit.SECONDS));
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            assertEquals(503, get(client, ProbeApplication.managementPort.get(), "/actuator/health/readiness"));

            ProbeApplication.releaseRunner.countDown();
            assertEquals(503, get(client, ProbeApplication.managementPort.get(), "/actuator/health/readiness"));
            ProbeApplication.dependenciesHealthy.set(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            int readinessStatus = 0;
            while (System.nanoTime() < deadline && readinessStatus != 200) {
                readinessStatus = get(client, ProbeApplication.managementPort.get(),
                        "/actuator/health/readiness");
                if (readinessStatus != 200) {
                    Thread.sleep(50);
                }
            }
            assertEquals(200, readinessStatus);
            assertEquals(200, get(client, ProbeApplication.managementPort.get(), "/actuator/health"));
            assertEquals(404, get(client, ProbeApplication.servicePort.get(), "/actuator/health/readiness"));
        } finally {
            ProbeApplication.releaseRunner.countDown();
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
    @EnableAutoConfiguration
    static class ProbeApplication {
        private static CountDownLatch serverStarted;
        private static CountDownLatch managementServerStarted;
        private static CountDownLatch releaseRunner;
        private static AtomicInteger servicePort;
        private static AtomicInteger managementPort;
        private static AtomicBoolean dependenciesHealthy;

        @Bean
        ApplicationRunner delayedStartupRunner() {
            return args -> releaseRunner.await();
        }

        @Bean
        ApplicationListener<WebServerInitializedEvent> webServerListener() {
            return event -> {
                if (event.getApplicationContext().getServerNamespace() == null) {
                    servicePort.set(event.getWebServer().getPort());
                    serverStarted.countDown();
                } else {
                    managementPort.set(event.getWebServer().getPort());
                    managementServerStarted.countDown();
                }
            };
        }

        @Bean
        HealthIndicator db() {
            return () -> dependenciesHealthy.get() ? Health.up().build() : Health.down().build();
        }

        @Bean
        HealthIndicator redis() {
            return () -> dependenciesHealthy.get() ? Health.up().build() : Health.down().build();
        }
    }
}

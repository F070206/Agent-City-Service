package com.hmdp.ai.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAgentTaskExecutorMetricsEndpointTest {
    @Test
    void exposesAgentExecutorMetricsOnManagementPortOnly() throws Exception {
        MetricsApplication.serverStarted = new CountDownLatch(1);
        MetricsApplication.servicePort = new AtomicInteger();
        MetricsApplication.managementPort = new AtomicInteger();

        AtomicReference<ConfigurableApplicationContext> context = new AtomicReference<>();
        Thread starter = new Thread(() -> context.set(new SpringApplication(MetricsApplication.class).run(
                "--server.port=0", "--management.server.port=0",
                "--management.endpoints.web.exposure.include=metrics",
                "--spring.main.banner-mode=off")), "ai-metrics-endpoint-test");
        starter.setDaemon(true);
        starter.start();

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            assertTrue(MetricsApplication.serverStarted.await(45, TimeUnit.SECONDS));
            int managementPort = awaitMetricsEndpoint(client);
            assertTrue(managementPort > 0);

            for (String metric : new String[]{
                    "active", "queued", "queue.remaining", "pool.size", "rejected"}) {
                assertEquals(200, get(client, managementPort,
                        "/actuator/metrics/cityai.ai.agent.executor." + metric));
            }
            assertEquals(404, get(client, MetricsApplication.servicePort.get(),
                    "/actuator/metrics/cityai.ai.agent.executor.active"));
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

    private int awaitMetricsEndpoint(HttpClient client) throws Exception {
        String path = "/actuator/metrics/cityai.ai.agent.executor.active";
        for (int attempt = 0; attempt < 100; attempt++) {
            int port = MetricsApplication.managementPort.get();
            if (port > 0 && get(client, port, path) == 200) {
                return port;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("AI management metrics endpoint did not become available");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
            "org.redisson.spring.starter.RedissonAutoConfigurationV2",
            "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
            "org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration",
            "com.alibaba.cloud.nacos.NacosDiscoveryAutoConfiguration",
            "com.alibaba.cloud.nacos.endpoint.NacosDiscoveryEndpointAutoConfiguration",
            "com.alibaba.cloud.nacos.registry.NacosServiceRegistryAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration",
            "org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration"
    })
    @Import(AiAsyncConfiguration.class)
    static class MetricsApplication {
        private static CountDownLatch serverStarted;
        private static AtomicInteger servicePort;
        private static AtomicInteger managementPort;

        @Bean
        ApplicationListener<WebServerInitializedEvent> webServerListener() {
            return event -> {
                if (event.getApplicationContext().getServerNamespace() == null) {
                    servicePort.set(event.getWebServer().getPort());
                } else {
                    managementPort.set(event.getWebServer().getPort());
                }
                serverStarted.countDown();
            };
        }
    }
}

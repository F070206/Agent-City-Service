package com.hmdp.component;

import com.hmdp.entity.OrderStockSyncBacklog;
import com.hmdp.mapper.OrderStockSyncMapper;
import com.hmdp.entity.SeckillOrderDispatchBacklog;
import com.hmdp.mapper.SeckillOrderDispatchMapper;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderStockSyncMetricsEndpointTest {

    @Test
    void exposesStockSyncBacklogGaugesThroughActuator() throws Exception {
        MetricsApplication.serverStarted = new CountDownLatch(1);
        MetricsApplication.managementPort = new AtomicInteger();
        OrderStockSyncBacklog snapshot = new OrderStockSyncBacklog();
        snapshot.setPendingCount(7);
        snapshot.setOldestPendingAgeSeconds(83);
        snapshot.setRepeatedFailureCount(2);
        MetricsApplication.backlog = mock(OrderStockSyncMapper.class);
        when(MetricsApplication.backlog.getBacklogSnapshot(3)).thenReturn(snapshot);
        MetricsApplication.dispatch = mock(SeckillOrderDispatchMapper.class);
        SeckillOrderDispatchBacklog dispatchSnapshot = new SeckillOrderDispatchBacklog();
        dispatchSnapshot.setOrderPendingCount(5);
        dispatchSnapshot.setOrderOldestPendingAgeSeconds(27);
        dispatchSnapshot.setTimeoutPendingCount(2);
        dispatchSnapshot.setTimeoutOldestPendingAgeSeconds(9);
        when(MetricsApplication.dispatch.getBacklogSnapshot(3)).thenReturn(dispatchSnapshot);
        MetricsApplication.redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> recoveryQueue = mock(ZSetOperations.class);
        when(MetricsApplication.redis.opsForZSet()).thenReturn(recoveryQueue);
        when(recoveryQueue.zCard("seckill:dispatch:recovery")).thenReturn(6L);

        AtomicReference<ConfigurableApplicationContext> context = new AtomicReference<>();
        Thread starter = new Thread(() -> context.set(new SpringApplication(MetricsApplication.class).run(
                "--server.port=0", "--management.endpoints.web.exposure.include=metrics",
                "--management.server.port=0",
                "--spring.main.banner-mode=off")), "stock-metrics-endpoint-test");
        starter.setDaemon(true);
        starter.start();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            assertTrue(MetricsApplication.serverStarted.await(15, TimeUnit.SECONDS));
            int managementPort = awaitMetricsEndpoint(client);
            assertTrue(managementPort > 0);
            assertEquals(200, get(client, managementPort,
                    "/actuator/metrics/cityai.order.stock.sync.oldest.age"));
            assertEquals(200, get(client, managementPort,
                    "/actuator/metrics/cityai.order.stock.sync.repeated.failures"));
            assertEquals(200, get(client, managementPort,
                    "/actuator/metrics/cityai.seckill.dispatch.orders.pending"));
            assertEquals(200, get(client, managementPort,
                    "/actuator/metrics/cityai.seckill.dispatch.timeouts.oldest.age"));
            assertEquals(200, get(client, managementPort,
                    "/actuator/metrics/cityai.seckill.dispatch.redis.recovery.pending"));
            assertEquals(404, get(client, MetricsApplication.port,
                    "/actuator/metrics/cityai.order.stock.sync.pending"));
        } finally {
            ConfigurableApplicationContext started = context.get();
            if (started != null) started.close();
            starter.join(TimeUnit.SECONDS.toMillis(10));
        }
    }

    private int get(HttpClient client, int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(2)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int awaitMetricsEndpoint(HttpClient client) throws Exception {
        String path = "/actuator/metrics/cityai.order.stock.sync.pending";
        for (int attempt = 0; attempt < 100; attempt++) {
            int managementPort = MetricsApplication.managementPort.get();
            if (managementPort > 0 && get(client, managementPort, path) == 200) {
                return managementPort;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("Management metrics endpoint did not become available");
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
    static class MetricsApplication {
        private static int port;
        private static AtomicInteger managementPort;
        private static CountDownLatch serverStarted;
        private static OrderStockSyncMapper backlog;
        private static SeckillOrderDispatchMapper dispatch;
        private static StringRedisTemplate redis;

        @Bean
        OrderStockSyncMapper orderStockSyncMapper() {
            return backlog;
        }

        @Bean
        OrderStockSyncMetrics orderStockSyncMetrics(OrderStockSyncMapper mapper,
                io.micrometer.core.instrument.MeterRegistry registry) {
            return new OrderStockSyncMetrics(mapper, registry);
        }

        @Bean
        SeckillOrderDispatchMapper seckillOrderDispatchMapper() {
            return dispatch;
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return redis;
        }

        @Bean
        SeckillOrderDispatchMetrics seckillOrderDispatchMetrics(SeckillOrderDispatchMapper mapper,
                io.micrometer.core.instrument.MeterRegistry registry) {
            SeckillOrderDispatchMetrics metrics = new SeckillOrderDispatchMetrics(mapper, redis, registry, 3);
            metrics.refreshSnapshot();
            return metrics;
        }

        @Bean
        ApplicationListener<WebServerInitializedEvent> webServerListener() {
            return event -> {
                if (event.getApplicationContext().getServerNamespace() == null) {
                    port = event.getWebServer().getPort();
                } else {
                    managementPort.set(event.getWebServer().getPort());
                }
                serverStarted.countDown();
            };
        }

    }
}

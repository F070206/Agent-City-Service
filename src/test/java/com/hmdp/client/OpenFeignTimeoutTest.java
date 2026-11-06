package com.hmdp.client;

import com.sun.net.httpserver.HttpServer;
import feign.RetryableException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenFeignTimeoutTest {
    @Test
    void microProfileDefinesTimeoutsAndFeignAppliesReadTimeout() throws IOException {
        var source = new YamlPropertySourceLoader().load("micro", new ClassPathResource("application-micro.yaml"))
                .get(0);
        assertEquals("${CITYAI_INTERNAL_CLIENT_CONNECT_TIMEOUT_MS:500}",
                source.getProperty("spring.cloud.openfeign.client.config.default.connectTimeout"));
        assertEquals("${CITYAI_INTERNAL_CLIENT_READ_TIMEOUT_MS:2000}",
                source.getProperty("spring.cloud.openfeign.client.config.default.readTimeout"));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch delayedRequestStarted = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            delayedRequestStarted.countDown();
            try {
                Thread.sleep(1500);
                byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            FeignAutoConfiguration.class,
                            HttpMessageConvertersAutoConfiguration.class))
                    .withUserConfiguration(FeignTestConfiguration.class)
                    .withPropertyValues(
                            "spring.cloud.openfeign.client.config.default.connectTimeout=2000",
                            "spring.cloud.openfeign.client.config.default.readTimeout=250",
                            "timeout-test.feign-url=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        SlowFeignClient client = context.getBean(SlowFeignClient.class);
                        long start = System.nanoTime();

                        assertThrows(RetryableException.class, client::slow);

                        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
                        assertTrue(delayedRequestStarted.getCount() == 0);
                        assertTrue(elapsedMillis < 1300,
                                "Feign should stop waiting before the delayed server responds; elapsed=" + elapsedMillis + "ms");
                    });
        } finally {
            server.stop(0);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = SlowFeignClient.class)
    @EnableConfigurationProperties(FeignClientProperties.class)
    static class FeignTestConfiguration {
    }

    @org.springframework.cloud.openfeign.FeignClient(name = "timeout-test", url = "${timeout-test.feign-url}")
    interface SlowFeignClient {
        @GetMapping("/slow")
        String slow();
    }
}

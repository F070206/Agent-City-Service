package com.hmdp.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayHttpTimeoutConfigurationTest {
    @Test
    void gatewayHasAnExplicitShortConfigurableConnectTimeoutAndKeepsSseResponseWindow() throws Exception {
        var properties = new YamlPropertySourceLoader()
                .load("gateway", new ClassPathResource("application.yaml")).get(0);

        assertEquals("${GATEWAY_CONNECT_TIMEOUT_MS:2000}",
                properties.getProperty("spring.cloud.gateway.httpclient.connect-timeout"));
        assertEquals("120s", properties.getProperty("spring.cloud.gateway.httpclient.response-timeout"));
    }
}

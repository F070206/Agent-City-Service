package com.hmdp.config;

import feign.RequestTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InternalFeignConfigurationTest {
    @Test
    void internalFeignRequestsCarryConfiguredServiceToken() {
        RequestTemplate request = new RequestTemplate();

        new InternalFeignConfiguration()
                .internalTokenInterceptor("shared-secret")
                .apply(request);

        assertEquals("shared-secret", request.headers().get("X-Internal-Token").iterator().next());
    }
}

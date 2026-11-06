package com.hmdp.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile({"micro-content", "micro-ai"})
public class InternalFeignConfiguration {
    @Bean
    public RequestInterceptor internalTokenInterceptor(@Value("${cityai.internal.token}") String token) {
        return request -> request.header("X-Internal-Token", token);
    }
}

package com.hmdp.config;

import com.hmdp.client.InternalApiGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@Profile({"micro-user", "micro-content"})
public class InternalApiMvcConfiguration implements WebMvcConfigurer {
    private final InternalApiGuard guard;

    public InternalApiMvcConfiguration(InternalApiGuard guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new InternalApiInterceptor(guard))
                .addPathPatterns("/internal/**")
                .order(-1);
    }
}

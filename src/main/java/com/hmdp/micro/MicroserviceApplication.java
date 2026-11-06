package com.hmdp.micro;

import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootConfiguration
@EnableAutoConfiguration
@Profile("micro")
@EnableDiscoveryClient
@ComponentScan(basePackages = "com.hmdp", excludeFilters =
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = ServiceIsolationFilter.class))
public class MicroserviceApplication {
    private static final Set<String> ROLES = Set.of("user", "content", "order", "ai");

    public static void main(String[] args) {
        String role = System.getProperty("cityai.role", System.getenv("CITYAI_ROLE"));
        if (role == null || !ROLES.contains(role)) {
            throw new IllegalArgumentException("CITYAI_ROLE must be user, content, order, or ai");
        }
        System.setProperty("cityai.role", role);
        SpringApplication application = new SpringApplication(MicroserviceApplication.class);
        application.setAdditionalProfiles("micro", "micro-" + role);
        application.run(args);
    }
}

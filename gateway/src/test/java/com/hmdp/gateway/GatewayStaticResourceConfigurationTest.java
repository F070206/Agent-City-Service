package com.hmdp.gateway;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayStaticResourceConfigurationTest {
    @Test
    void servesFrontendAndSharedUploadsFromSeparateLocations() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("gateway", new ClassPathResource("application.yaml"))
                .forEach(environment.getPropertySources()::addFirst);

        List<String> locations = Binder.get(environment)
                .bind("spring.web.resources.static-locations", Bindable.listOf(String.class))
                .get();

        assertEquals(List.of("file:/app/static/", "file:/app/uploads/"), locations);
    }
}

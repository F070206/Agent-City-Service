package com.hmdp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UploadStaticLocationConfigurationTest {

    @Test
    void standaloneUploadDirectoryIsAlsoAStaticResourceRoot() throws IOException {
        StandardEnvironment environment = environment("application.yaml");

        String uploadDirectory = environment.getProperty("cityai.upload-dir");
        String staticLocations = environment.getProperty("spring.web.resources.static-locations");

        assertEquals("./uploads", uploadDirectory);
        assertTrue(staticLocations.contains("file:" + uploadDirectory + "/"));
    }

    @Test
    void microserviceUploadDirectoryIsAlsoAStaticResourceRoot() throws IOException {
        StandardEnvironment environment = environment("application-micro-content.yaml");

        String uploadDirectory = environment.getProperty("cityai.upload-dir");
        String staticLocations = environment.getProperty("spring.web.resources.static-locations");

        assertEquals("/data/imgs", uploadDirectory);
        assertTrue(staticLocations.contains("file:" + uploadDirectory + "/"));
        assertEquals("8MB", environment.getProperty("spring.servlet.multipart.max-file-size"));
        assertEquals("10MB", environment.getProperty("spring.servlet.multipart.max-request-size"));
    }

    @Test
    void customUploadDirectoryOverridesStorageAndStaticRootTogether() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("testOverrides",
                Map.of("CITYAI_UPLOAD_DIR", "C:/cityai/uploads")));
        new YamlPropertySourceLoader().load("test", new ClassPathResource("application.yaml"))
                .forEach(environment.getPropertySources()::addLast);

        String uploadDirectory = environment.getProperty("cityai.upload-dir");
        String staticLocations = environment.getProperty("spring.web.resources.static-locations");

        assertEquals("C:/cityai/uploads", uploadDirectory);
        assertTrue(staticLocations.contains("file:" + uploadDirectory + "/"));
    }

    @Test
    void uploadLimitsAreExplicitAndAllowTypicalPhonePhotos() throws IOException {
        StandardEnvironment environment = environment("application.yaml");

        assertEquals("8MB", environment.getProperty("spring.servlet.multipart.max-file-size"));
        assertEquals("10MB", environment.getProperty("spring.servlet.multipart.max-request-size"));
    }

    @Test
    void mvcServesUploadedBlogImageFromConfiguredFileRoot() throws Exception {
        Path uploadRoot = Files.createTempDirectory("cityai-upload-static-");
        byte[] imageBytes = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        Path image = uploadRoot.resolve("blogs/example.png");
        Files.createDirectories(image.getParent());
        Files.write(image, imageBytes);

        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class))
                .withPropertyValues("spring.web.resources.static-locations=" + uploadRoot.toUri())
                .run(context -> MockMvcBuilders.webAppContextSetup(context).build()
                        .perform(get("/blogs/example.png"))
                        .andExpect(status().isOk())
                        .andExpect(content().bytes(imageBytes)));
    }

    private StandardEnvironment environment(String resourceName) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("test", new ClassPathResource(resourceName))
                .forEach(environment.getPropertySources()::addLast);
        return environment;
    }
}

package com.hmdp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import com.hmdp.service.IBlogService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UploadControllerSecurityTest {
    @Test
    @SuppressWarnings("unchecked")
    void authenticatedUsersCannotUploadNonImageFiles() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("login:token:valid-token")).thenReturn(Map.of("id", "7"));
        when(redis.expire("login:token:valid-token", 36000L, java.util.concurrent.TimeUnit.MINUTES))
                .thenReturn(true);
        Path uploadDir = Files.createTempDirectory("upload-non-image-test-");
        MockMvc mvc = mvc(redis, uploadDir, mock(IBlogService.class));

        mvc.perform(multipart("/upload/blog")
                        .file(new MockMultipartFile("file", "payload.html", "text/html", "<script>alert(1)</script>".getBytes()))
                        .header("authorization", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
                    assertFalse(body.path("success").asBoolean());
                });

        try (Stream<Path> files = Files.walk(uploadDir)) {
            assertEquals(0L, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void authenticatedUsersCannotUploadEmptyOrMismatchedImageFiles() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("login:token:valid-token")).thenReturn(Map.of("id", "7"));
        when(redis.expire("login:token:valid-token", 36000L, java.util.concurrent.TimeUnit.MINUTES))
                .thenReturn(true);
        Path uploadDir = Files.createTempDirectory("upload-invalid-image-test-");
        MockMvc mvc = mvc(redis, uploadDir, mock(IBlogService.class));

        for (MockMultipartFile file : new MockMultipartFile[] {
                new MockMultipartFile("file", "empty.png", "image/png", new byte[0]),
                new MockMultipartFile("file", "mismatch.png", "text/html", "<html>no</html>".getBytes())
        }) {
            mvc.perform(multipart("/upload/blog").file(file).header("authorization", "valid-token"))
                    .andExpect(status().isOk())
                    .andExpect(result -> assertFalse(new ObjectMapper()
                            .readTree(result.getResponse().getContentAsString()).path("success").asBoolean()));
        }

        try (Stream<Path> files = Files.walk(uploadDir)) {
            assertEquals(0L, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void authenticatedUsersCannotSpoofImageExtensionAndContentType() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("login:token:valid-token")).thenReturn(Map.of("id", "7"));
        when(redis.expire("login:token:valid-token", 36000L, java.util.concurrent.TimeUnit.MINUTES))
                .thenReturn(true);
        Path uploadDir = Files.createTempDirectory("upload-spoofed-image-test-");
        MockMvc mvc = mvc(redis, uploadDir, mock(IBlogService.class));

        mvc.perform(multipart("/upload/blog")
                        .file(new MockMultipartFile("file", "payload.png", "image/png",
                                "<script>alert(1)</script>".getBytes()))
                        .header("authorization", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(result -> assertFalse(new ObjectMapper()
                        .readTree(result.getResponse().getContentAsString()).path("success").asBoolean()));

        try (Stream<Path> files = Files.walk(uploadDir)) {
            assertEquals(0L, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void authenticatedUsersReceiveControlledFailureWhenFilenameIsMissing() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("login:token:valid-token")).thenReturn(Map.of("id", "7"));
        when(redis.expire("login:token:valid-token", 36000L, java.util.concurrent.TimeUnit.MINUTES))
                .thenReturn(true);
        Path uploadDir = Files.createTempDirectory("upload-missing-filename-test-");
        MockMvc mvc = mvc(redis, uploadDir, mock(IBlogService.class));

        mvc.perform(multipart("/upload/blog")
                        .file(new MockMultipartFile("file", null, "image/png", new byte[] {
                                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
                        }))
                        .header("authorization", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(result -> assertFalse(new ObjectMapper()
                        .readTree(result.getResponse().getContentAsString()).path("success").asBoolean()));
    }


    @Test
    @SuppressWarnings("unchecked")
    void anonymousUsersCannotUploadOrDeleteImages() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("login:token:anonymous-token")).thenReturn(Map.of());
        MockMvc mvc = mvc(redis, Files.createTempDirectory("upload-security-test-"), mock(IBlogService.class));

        mvc.perform(multipart("/upload/blog").file(
                        new MockMultipartFile("file", "photo.png", "image/png", new byte[] {1})))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/upload/blog").param("name", "/blogs/known-photo.png"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @SuppressWarnings("unchecked")
    void authenticatedUsersCanStillUploadImages() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("login:token:valid-token")).thenReturn(Map.of("id", "7"));
        when(redis.expire("login:token:valid-token", 36000L, java.util.concurrent.TimeUnit.MINUTES))
                .thenReturn(true);
        Path uploadDir = Files.createTempDirectory("upload-authenticated-test-");
        MockMvc mvc = mvc(redis, uploadDir, mock(IBlogService.class));

        MockHttpServletResponse response = mvc.perform(multipart("/upload/blog")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", new byte[] {
                                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                                0x00, 0x00, 0x00, 0x00
                        }))
                        .header("authorization", "valid-token"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        String filename = response.getContentAsString().replaceAll(".*\\\"data\\\":\\\"([^\\\"]+)\\\".*", "$1");
        assertFalse(filename.isBlank());
        assertTrue(filename.startsWith("/blogs/7/"));
        try (Stream<Path> files = Files.walk(uploadDir)) {
            assertEquals(1L, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void authenticatedUsersCannotDeleteImagesNotReferencedByTheirBlogs() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("login:token:valid-token")).thenReturn(Map.of("id", "7"));
        when(redis.expire("login:token:valid-token", 36000L, java.util.concurrent.TimeUnit.MINUTES))
                .thenReturn(true);
        IBlogService blogService = mock(IBlogService.class);
        when(blogService.ownsImage(7L, "/blogs/8/known-photo.png")).thenReturn(false);
        Path uploadDir = Files.createTempDirectory("upload-owner-test-");
        Path target = uploadDir.resolve("blogs/8/known-photo.png");
        Files.createDirectories(target.getParent());
        Files.write(target, new byte[] {1});
        MockMvc mvc = mvc(redis, uploadDir, blogService);

        mvc.perform(delete("/upload/blog").param("name", "/blogs/8/known-photo.png")
                        .header("authorization", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(result -> assertFalse(new ObjectMapper()
                        .readTree(result.getResponse().getContentAsString()).path("success").asBoolean()));

        assertTrue(Files.exists(target));
    }

    @Test
    @SuppressWarnings("unchecked")
    void authenticatedUsersCanDeleteTheirUnpublishedDraftImages() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("login:token:valid-token")).thenReturn(Map.of("id", "7"));
        when(redis.expire("login:token:valid-token", 36000L, java.util.concurrent.TimeUnit.MINUTES))
                .thenReturn(true);
        Path uploadDir = Files.createTempDirectory("upload-draft-delete-test-");
        Path target = uploadDir.resolve("blogs/7/draft.png");
        Files.createDirectories(target.getParent());
        Files.write(target, new byte[] {1});
        MockMvc mvc = mvc(redis, uploadDir, mock(IBlogService.class));

        mvc.perform(delete("/upload/blog").param("name", "/blogs/7/draft.png")
                        .header("authorization", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(result -> assertTrue(new ObjectMapper()
                        .readTree(result.getResponse().getContentAsString()).path("success").asBoolean()));

        assertFalse(Files.exists(target));
    }

    @Test
    @SuppressWarnings("unchecked")
    void authenticatedUsersCannotDeleteImagesReferencedByPublishedBlogs() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("login:token:valid-token")).thenReturn(Map.of("id", "7"));
        when(redis.expire("login:token:valid-token", 36000L, java.util.concurrent.TimeUnit.MINUTES))
                .thenReturn(true);
        IBlogService blogService = mock(IBlogService.class);
        when(blogService.ownsImage(7L, "/blogs/7/published.png")).thenReturn(true);
        Path uploadDir = Files.createTempDirectory("upload-published-image-test-");
        Path target = uploadDir.resolve("blogs/7/published.png");
        Files.createDirectories(target.getParent());
        Files.write(target, new byte[] {1});
        MockMvc mvc = mvc(redis, uploadDir, blogService);

        mvc.perform(delete("/upload/blog").param("name", "/blogs/7/published.png")
                        .header("authorization", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(result -> assertFalse(new ObjectMapper()
                        .readTree(result.getResponse().getContentAsString()).path("success").asBoolean()));

        assertTrue(Files.exists(target));
    }

    private MockMvc mvc(StringRedisTemplate redis, Path uploadDir, IBlogService blogService) {
        UploadController controller = new UploadController();
        ReflectionTestUtils.setField(controller, "uploadDir", uploadDir.toString());
        ReflectionTestUtils.setField(controller, "blogService", blogService);
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new RefreshTokenInterceptor(redis), new LoginInterceptor())
                .build();
    }
}

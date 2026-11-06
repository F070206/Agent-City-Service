package com.hmdp.utils;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "AUTH_TEST_REDIS_HOST", matches = ".+")
class RefreshTokenInterceptorRedisTest {
    private LettuceConnectionFactory writerConnection;
    private LettuceConnectionFactory readerConnection;
    private StringRedisTemplate writer;
    private StringRedisTemplate reader;
    private String token;
    private String key;

    @BeforeEach
    void connectToIsolatedRedisDatabase() {
        int port = Integer.parseInt(System.getenv().getOrDefault("AUTH_TEST_REDIS_PORT", "6379"));
        int database = Integer.parseInt(System.getenv().getOrDefault("AUTH_TEST_REDIS_DATABASE", "15"));
        writerConnection = connectionFactory(port, database);
        readerConnection = connectionFactory(port, database);
        writer = new StringRedisTemplate(writerConnection);
        reader = new StringRedisTemplate(readerConnection);
        writer.afterPropertiesSet();
        reader.afterPropertiesSet();
        token = "auth-integration-" + UUID.randomUUID();
        key = "login:token:" + token;
    }

    @AfterEach
    void removeOnlyThisTestToken() {
        if (writer != null && key != null) {
            writer.delete(key);
        }
        if (readerConnection != null) {
            readerConnection.destroy();
        }
        if (writerConnection != null) {
            writerConnection.destroy();
        }
    }

    @Test
    void sharedTokenCanBeReadRefreshedAndRevokedAcrossRedisClients() throws Exception {
        writer.opsForHash().putAll(key, Map.of("id", "731", "nickName", "integration-user"));
        writer.expire(key, 1, TimeUnit.MINUTES);

        RefreshTokenInterceptor interceptor = new RefreshTokenInterceptor(reader);
        MockHttpServletRequest authenticatedRequest = requestWithToken();
        assertTrue(interceptor.preHandle(authenticatedRequest, new MockHttpServletResponse(), new Object()));
        assertEquals(731L, UserHolder.getUser().getId());
        assertTrue(reader.getExpire(key, TimeUnit.MINUTES) > 100);
        UserHolder.removeUser();

        writer.delete(key);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(requestWithToken(), response, new Object()));
        assertFalse(new LoginInterceptor().preHandle(requestWithToken(), response, new Object()));
        assertEquals(401, response.getStatus());
    }

    private MockHttpServletRequest requestWithToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("authorization", token);
        return request;
    }

    private LettuceConnectionFactory connectionFactory(int port, int database) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                System.getenv("AUTH_TEST_REDIS_HOST"), port);
        factory.setDatabase(database);
        String password = System.getenv("AUTH_TEST_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) {
            factory.setPassword(password);
        }
        factory.afterPropertiesSet();
        return factory;
    }
}

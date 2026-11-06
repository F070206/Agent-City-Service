package com.hmdp.component;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "BLOG_FEED_TEST_REDIS_HOST", matches = ".+")
class BlogFeedRetentionRedisTest {
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private DefaultRedisScript<Long> script;
    private String key;

    @BeforeEach
    void setUp() {
        connection = new LettuceConnectionFactory(System.getenv("BLOG_FEED_TEST_REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("BLOG_FEED_TEST_REDIS_PORT", "6379")));
        String password = System.getenv().getOrDefault("BLOG_FEED_TEST_REDIS_PASSWORD", "");
        if (!password.isEmpty()) {
            connection.getStandaloneConfiguration().setPassword(password);
        }
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        key = "test:blog:feed:" + UUID.randomUUID();
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("blog-feed-upsert.lua"));
        script.setResultType(Long.class);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key)), "random test feed key already exists");
    }

    @AfterEach
    void cleanUp() {
        try {
            if (redis != null && key != null) {
                redis.delete(key);
            }
        } finally {
            if (connection != null) {
                connection.destroy();
            }
        }
    }

    @Test
    void feedKeepsNewestEntriesAndReplayingAnEntryIsIdempotent() {
        for (int blogId = 1; blogId <= 5; blogId++) {
            redis.execute(script, List.of(key), Integer.toString(blogId), Integer.toString(blogId), "3");
        }

        assertEquals(3L, redis.opsForZSet().zCard(key));
        assertEquals(List.of("3", "4", "5"), redis.opsForZSet().range(key, 0, -1).stream().toList());
        assertEquals(3L, redis.execute(script, List.of(key), "3", "3", "3"));
        assertEquals(3L, redis.opsForZSet().zCard(key));
        assertTrue(redis.opsForZSet().score(key, "3") < redis.opsForZSet().score(key, "5"));
    }
}

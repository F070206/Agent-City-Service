package com.hmdp.component;

import com.hmdp.dto.SeckillVoucherLookup;
import com.hmdp.mapper.SeckillVoucherMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_REDIS_HOST", matches = ".+")
class SeckillActivityCacheRedisTest {
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private String cacheKey;

    @BeforeEach
    void setUp() {
        connection = new LettuceConnectionFactory(System.getenv("PAYMENT_TEST_REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("PAYMENT_TEST_REDIS_PORT", "6379")));
        String password = System.getenv().getOrDefault("PAYMENT_TEST_REDIS_PASSWORD", "");
        if (!password.isEmpty()) {
            connection.getStandaloneConfiguration().setPassword(password);
        }
        connection.getStandaloneConfiguration().setDatabase(Integer.parseInt(
                System.getenv().getOrDefault("PAYMENT_TEST_REDIS_DATABASE", "0")));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        long voucherId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        cacheKey = "seckill:activity:" + voucherId;
    }

    @AfterEach
    void cleanUp() {
        try {
            if (redis != null && cacheKey != null) {
                redis.delete(cacheKey);
            }
        } finally {
            if (connection != null) {
                connection.destroy();
            }
        }
    }

    @Test
    void delayedOlderRefreshCannotReplaceNewerActivityMetadata() {
        SeckillActivityCache cache = new SeckillActivityCache(redis, mock(SeckillVoucherMapper.class));
        Long voucherId = Long.valueOf(cacheKey.substring("seckill:activity:".length()));
        cache.put(activity(voucherId, 2L, 0, 20));
        cache.put(activity(voucherId, 1L, 1, 10));

        assertEquals("2", redis.<String, String>opsForHash().get(cacheKey, "version"));
        assertEquals("0", redis.<String, String>opsForHash().get(cacheKey, "voucherStatus"));
        String expectedEnd = LocalDateTime.of(2026, 11, 4, 10, 0).plusSeconds(20)
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli() + "";
        assertEquals(expectedEnd, redis.<String, String>opsForHash().get(cacheKey, "endTime"));
    }

    private SeckillVoucherLookup activity(Long voucherId, Long version, int status, int endEpochMillis) {
        SeckillVoucherLookup activity = new SeckillVoucherLookup();
        activity.setVoucherId(voucherId);
        activity.setVersion(version);
        activity.setVoucherStatus(status);
        activity.setVoucherType(1);
        activity.setBeginTime(LocalDateTime.of(2026, 11, 4, 10, 0));
        activity.setEndTime(LocalDateTime.of(2026, 11, 4, 10, 0).plusSeconds(endEpochMillis));
        return activity;
    }
}

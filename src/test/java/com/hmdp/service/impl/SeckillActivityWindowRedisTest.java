package com.hmdp.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Instant;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_REDIS_HOST", matches = ".+")
class SeckillActivityWindowRedisTest {
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private DefaultRedisScript<Long> script;
    private String voucherId;
    private String stockKey;
    private String orderKey;
    private String reservationKey;
    private String pendingKey;
    private String payloadKey;
    private String recoveryKey;
    private String activityKey;
    private String orderId;

    @BeforeEach
    void setUp() throws IOException {
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
        voucherId = UUID.randomUUID().toString();
        orderId = UUID.randomUUID().toString().replace("-", "");
        stockKey = "seckill:stock:" + voucherId;
        activityKey = "seckill:activity:" + voucherId;
        orderKey = "seckill:order:" + voucherId;
        reservationKey = "seckill:order:reservation:" + voucherId;
        pendingKey = "seckill:order:pending:" + orderId;
        payloadKey = "seckill:dispatch:payload:" + orderId;
        recoveryKey = "test:seckill:dispatch:recovery:" + UUID.randomUUID();
        redis.opsForValue().set(stockKey, "2");
        script = new DefaultRedisScript<>();
        String scriptText = new ClassPathResource("seckill.lua").getContentAsString(StandardCharsets.UTF_8)
                .replace("seckill:dispatch:recovery", recoveryKey);
        script.setScriptText(scriptText);
        script.setResultType(Long.class);
    }

    @AfterEach
    void cleanUp() {
        try {
            if (redis != null && stockKey != null) {
                redis.delete(List.of(stockKey, activityKey, orderKey, reservationKey, pendingKey, payloadKey,
                        recoveryKey));
            }
        } finally {
            if (connection != null) {
                connection.destroy();
            }
        }
    }

    @Test
    void redisTimeRejectsReservationOutsideWindowWithoutChangingStock() {
        long now = Instant.now().toEpochMilli();
        assertEquals(4L, reserve(now + 60_000, now + 120_000));
        assertEquals(5L, reserve(now - 120_000, now - 60_000));
        assertEquals("2", redis.opsForValue().get(stockKey));
        assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(orderKey, "7")));
        assertEquals(0L, reserve(now - 60_000, now + 60_000));
        assertEquals("1", redis.opsForValue().get(stockKey));
        assertEquals(2L, reserve(now - 60_000, now + 60_000));
        assertEquals("1", redis.opsForValue().get(stockKey));
        assertEquals("7", redis.opsForValue().get(pendingKey));
        assertEquals(orderId, redis.<String, String>opsForHash().get(reservationKey, "7"));
        assertTrue(redis.opsForValue().get(payloadKey).contains(orderId));
        assertTrue(Boolean.TRUE.equals(redis.opsForZSet().score(recoveryKey, orderId) != null));
    }

    @Test
    void cachedVoucherStatusAndTypeAreCheckedAtomicallyBeforeReservation() {
        long now = Instant.now().toEpochMilli();
        redis.opsForHash().putAll(activityKey, Map.of(
                "beginTime", Long.toString(now - 60_000),
                "endTime", Long.toString(now + 60_000),
                "voucherStatus", "0",
                "voucherType", "1"));
        assertEquals(7L, reserveWithoutChangingActivity(now - 60_000, now + 60_000));
        redis.opsForHash().put(activityKey, "voucherStatus", "1");
        redis.opsForHash().put(activityKey, "voucherType", "0");
        assertEquals(7L, reserveWithoutChangingActivity(now - 60_000, now + 60_000));
        assertEquals("2", redis.opsForValue().get(stockKey));
    }

    @Test
    void wrongTypeRecoveryQueueDoesNotPartiallyReserveInventory() {
        long now = Instant.now().toEpochMilli();
        redis.opsForHash().putAll(activityKey, Map.of(
                "beginTime", Long.toString(now - 60_000),
                "endTime", Long.toString(now + 60_000),
                "voucherStatus", "1",
                "voucherType", "1"));
        redis.opsForValue().set(recoveryKey, "wrong-type");

        assertEquals(8L, reserveWithoutChangingActivity(now - 60_000, now + 60_000));
        assertEquals("2", redis.opsForValue().get(stockKey));
        assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(orderKey, "7")));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(pendingKey)));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(payloadKey)));
    }

    private Long reserve(long begin, long end) {
        redis.opsForHash().putAll(activityKey, Map.of(
                "beginTime", Long.toString(begin),
                "endTime", Long.toString(end),
                "voucherStatus", "1",
                "voucherType", "1"));
        return reserveWithoutChangingActivity(begin, end);
    }

    private Long reserveWithoutChangingActivity(long begin, long end) {
        return redis.execute(script, Collections.emptyList(), voucherId, "7", orderId,
                "{\"id\":\"" + orderId + "\",\"voucherId\":\"" + voucherId + "\",\"userId\":7}");
    }
}

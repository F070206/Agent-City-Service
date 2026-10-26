package com.hmdp.component;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** 仅创建与删除独立 UUID 前缀的测试键，不使用业务键或 FLUSHDB。 */
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_REDIS_HOST", matches = ".+")
class OrderStockReleaseIntegrationTest {

    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private List<String> keys;
    private DefaultRedisScript<Long> script;

    @BeforeEach
    void setUp() {
        connection = new LettuceConnectionFactory(System.getenv("PAYMENT_TEST_REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("PAYMENT_TEST_REDIS_PORT", "6379")));
        connection.getStandaloneConfiguration().setPassword(
                System.getenv().getOrDefault("PAYMENT_TEST_REDIS_PASSWORD", ""));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        String prefix = "test:payment:" + UUID.randomUUID() + ":";
        keys = List.of(prefix + "stock", prefix + "users", prefix + "released", prefix + "pending");
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("release-order-stock.lua"));
        script.setResultType(Long.class);
        redis.opsForValue().set(keys.get(0), "0");
        redis.opsForSet().add(keys.get(1), "7");
        redis.opsForValue().set(keys.get(3), "7");
    }

    @AfterEach
    void cleanUp() {
        try {
            if (redis != null && keys != null) {
                redis.delete(keys);
            }
        } finally {
            if (connection != null) {
                connection.destroy();
            }
        }
    }

    @Test
    void retryDoesNotReleaseNewReservationForSameUser() {
        assertEquals(1L, release());
        assertEquals("1", redis.opsForValue().get(keys.get(0)));
        assertFalse(redis.hasKey(keys.get(3)));
        assertEquals(-1L, redis.getExpire(keys.get(2)));
        // 模拟回补成功但数据库 ACK 失败后，用户再次抢购。
        redis.opsForValue().decrement(keys.get(0));
        redis.opsForSet().add(keys.get(1), "7");
        assertEquals(0L, release());
        assertEquals("0", redis.opsForValue().get(keys.get(0)));
        assertTrue(redis.opsForSet().isMember(keys.get(1), "7"));
    }

    @Test
    void concurrentWorkersRestoreExactlyOnce() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Long>> requests = java.util.stream.IntStream.range(0, 32)
                    .mapToObj(i -> (Callable<Long>) this::release).toList();
            long applied = 0;
            for (Future<Long> result : executor.invokeAll(requests, 10, TimeUnit.SECONDS)) {
                applied += result.get();
            }
            assertEquals(1L, applied);
            assertEquals("1", redis.opsForValue().get(keys.get(0)));
            assertFalse(redis.opsForSet().isMember(keys.get(1), "7"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void missingStockDoesNotInventInventoryOrReleaseUser() {
        redis.delete(keys.get(0));
        assertEquals(-1L, release());
        assertFalse(redis.hasKey(keys.get(0)));
        assertTrue(redis.opsForSet().isMember(keys.get(1), "7"));
        assertFalse(redis.hasKey(keys.get(2)));
    }

    @Test
    void missingReservationLeavesTaskUnapplied() {
        redis.delete(keys.get(1));
        assertEquals(-2L, release());
        assertEquals("0", redis.opsForValue().get(keys.get(0)));
        assertFalse(redis.hasKey(keys.get(2)));
    }

    @Test
    void wrongReservationTypeFailsBeforeChangingStock() {
        redis.delete(keys.get(1));
        redis.opsForValue().set(keys.get(1), "invalid");
        assertThrows(RuntimeException.class, this::release);
        assertEquals("0", redis.opsForValue().get(keys.get(0)));
        assertFalse(redis.hasKey(keys.get(2)));
    }

    private Long release() {
        return redis.execute(script, keys, "7");
    }
}

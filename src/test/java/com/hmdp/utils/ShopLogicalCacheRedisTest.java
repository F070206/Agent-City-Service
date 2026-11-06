package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CACHE_TEST_REDIS_HOST", matches = ".+")
class ShopLogicalCacheRedisTest {

    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private String key;
    private String lockKey;
    private String id;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        connection = new LettuceConnectionFactory(System.getenv("CACHE_TEST_REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("CACHE_TEST_REDIS_PORT", "6379")));
        connection.setDatabase(Integer.parseInt(System.getenv().getOrDefault("CACHE_TEST_REDIS_DATABASE", "0")));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        meterRegistry = new SimpleMeterRegistry();
        id = UUID.randomUUID().toString();
        key = "test:shop:logical:" + id;
        lockKey = "lock:shop:" + id;
    }

    @AfterEach
    void cleanUp() {
        try {
            if (redis != null) {
                redis.delete(List.of(key, lockKey));
            }
        } finally {
            if (connection != null) {
                connection.destroy();
            }
            if (meterRegistry != null) {
                meterRegistry.close();
            }
        }
    }

    private CacheClient newCacheClient() {
        return new CacheClient(redis, new ShopCacheMetrics(meterRegistry));
    }

    @Test
    void coldMissLoadsThenReadsLogicalCache() {
        CacheClient cache = newCacheClient();
        Shop shop = new Shop();
        shop.setId(1L);

        assertEquals(1L, cache.queryWithLogicalExpire("test:shop:logical:", id,
                Shop.class, ignored -> shop, 30L, TimeUnit.SECONDS).getId());
        assertEquals(1L, cache.queryWithLogicalExpire("test:shop:logical:", id,
                Shop.class, ignored -> { throw new AssertionError("unexpected database query"); },
                30L, TimeUnit.SECONDS).getId());
        assertTrue(redis.getExpire(key) > 0);
    }

    @Test
    void concurrentColdMissLoadsFromDatabaseOnce() throws Exception {
        CacheClient cache = newCacheClient();
        Shop shop = new Shop();
        shop.setId(1L);
        AtomicInteger databaseReads = new AtomicInteger();
        int callers = 24;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Shop>> requests = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                requests.add(executor.submit(() -> {
                    start.await();
                    return cache.queryWithLogicalExpire("test:shop:logical:", id, Shop.class, ignored -> {
                        databaseReads.incrementAndGet();
                        try {
                            Thread.sleep(150);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return shop;
                    }, 30L, TimeUnit.SECONDS);
                }));
            }
            start.countDown();
            for (Future<Shop> request : requests) {
                assertEquals(1L, request.get(5, TimeUnit.SECONDS).getId());
            }
            assertEquals(1, databaseReads.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void slowMutexLoadRenewsRedisLeaseForConcurrentRequest() throws Exception {
        CacheClient cache = newCacheClient();
        CountDownLatch firstLoadStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstLoad = new CountDownLatch(1);
        AtomicInteger databaseReads = new AtomicInteger();
        Shop shop = new Shop();
        shop.setId(71L);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Shop> first = executor.submit(() -> cache.queryWithMutex(
                    "test:shop:logical:", id, Shop.class, ignored -> {
                        databaseReads.incrementAndGet();
                        firstLoadStarted.countDown();
                        try {
                            if (!releaseFirstLoad.await(20, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("first cache load was not released");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return shop;
                    }, 30L, TimeUnit.SECONDS));
            assertTrue(firstLoadStarted.await(2, TimeUnit.SECONDS));

            // Wait past two renewal intervals, then race a second client against the same Redis lock.
            Thread.sleep(8_000);
            Future<Shop> second = executor.submit(() -> cache.queryWithMutex(
                    "test:shop:logical:", id, Shop.class, ignored -> {
                        databaseReads.incrementAndGet();
                        return new Shop().setId(72L);
                    }, 30L, TimeUnit.SECONDS));
            Thread.sleep(3_500);
            assertEquals(1, databaseReads.get());

            releaseFirstLoad.countDown();
            assertEquals(71L, first.get(3, TimeUnit.SECONDS).getId());
            assertEquals(71L, second.get(3, TimeUnit.SECONDS).getId());
            assertEquals(1, databaseReads.get());
        } finally {
            releaseFirstLoad.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void lateEmptyResultCannotOverwriteCommittedShop() throws Exception {
        CacheClient cache = newCacheClient();
        CountDownLatch databaseReadStarted = new CountDownLatch(1);
        CountDownLatch commitFinished = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Shop> beforeCommit = executor.submit(() -> cache.queryWithLogicalExpire(
                    "test:shop:logical:", id, Shop.class, ignored -> {
                        databaseReadStarted.countDown();
                        try {
                            if (!commitFinished.await(2, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("commit did not finish");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return null;
                    }, 30L, TimeUnit.SECONDS));
            assertTrue(databaseReadStarted.await(2, TimeUnit.SECONDS));
            Shop committed = new Shop();
            committed.setId(42L);
            cache.setWithLogicalExpire(key, committed, 30L, TimeUnit.SECONDS);
            commitFinished.countDown();
            assertEquals(42L, beforeCommit.get(3, TimeUnit.SECONDS).getId());
            assertEquals(42L, cache.queryWithLogicalExpire("test:shop:logical:", id,
                    Shop.class, ignored -> { throw new AssertionError("unexpected database query"); },
                    30L, TimeUnit.SECONDS).getId());
        } finally {
            commitFinished.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void backgroundRefreshDoesNotOverwriteNewerValue() throws InterruptedException {
        CacheClient cache = newCacheClient();
        redis.opsForValue().set(key,
                "{\"data\":{\"id\":1},\"expireTime\":\"2020-01-01T00:00:00\"}",
                60, TimeUnit.SECONDS);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Shop stale = cache.queryWithLogicalExpire("test:shop:logical:", id, Shop.class, ignored -> {
            started.countDown();
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            Shop old = new Shop();
            old.setId(1L);
            return old;
        }, 30L, TimeUnit.SECONDS);
        assertEquals(1L, stale.getId());
        assertTrue(started.await(2, TimeUnit.SECONDS));

        Shop latest = new Shop();
        latest.setId(2L);
        cache.setWithLogicalExpire(key, latest, 30L, TimeUnit.SECONDS);
        release.countDown();
        for (int i = 0; i < 40 && Boolean.TRUE.equals(redis.hasKey(lockKey)); i++) {
            Thread.sleep(25);
        }
        assertEquals(2L, JSONUtil.parseObj(redis.opsForValue().get(key))
                .getJSONObject("data").getLong("id"));
    }
}

package com.hmdp.utils;

import com.hmdp.entity.Shop;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class CacheClientTest {

    @Test
    @SuppressWarnings("unchecked")
    void malformedShopCacheIsInvalidatedAndReloadedFromDatabase() {
        String key = "cache:shop:77";
        String malformed = "{bad json";
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(key)).thenReturn(malformed, null, null);
        when(redis.execute(any(), eq(java.util.List.of(key)), eq(malformed))).thenReturn(1L);
        when(values.setIfAbsent(eq("lock:shop:77"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(values.setIfAbsent(eq(key), anyString(), eq(3600L), eq(TimeUnit.SECONDS))).thenReturn(true);
        Shop shop = new Shop();
        shop.setId(77L);

        Shop result = new CacheClient(redis, new ShopCacheMetrics(new SimpleMeterRegistry()))
                .queryWithLogicalExpire("cache:shop:", 77L, Shop.class, id -> shop,
                        id -> true, 30L, TimeUnit.MINUTES);

        assertEquals(77L, result.getId());
        verify(redis).execute(any(), eq(java.util.List.of(key)), eq(malformed));
        verify(values).setIfAbsent(eq(key), anyString(), eq(3600L), eq(TimeUnit.SECONDS));
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedShopCacheDoesNotDeleteAConcurrentReplacement() {
        String key = "cache:shop:78";
        String malformed = "{bad json";
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(key)).thenReturn(malformed, "{\"id\":78}");
        when(redis.execute(any(), eq(java.util.List.of(key)), eq(malformed))).thenReturn(0L);

        Shop result = new CacheClient(redis, new ShopCacheMetrics(new SimpleMeterRegistry()))
                .queryWithLogicalExpire("cache:shop:", 78L, Shop.class,
                        id -> { throw new AssertionError("concurrent replacement must be preserved"); },
                        id -> true, 30L, TimeUnit.MINUTES);

        assertEquals(78L, result.getId());
        verify(redis).execute(any(), eq(java.util.List.of(key)), eq(malformed));
        verify(values, never()).setIfAbsent(eq("lock:shop:78"), anyString(), anyLong(), any());
    }

    @Test
    void springCreatesCacheClientWithItsInjectedConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
            context.registerBean(ShopCacheMetrics.class,
                    () -> new ShopCacheMetrics(new SimpleMeterRegistry()));
            context.registerBean(ShopCacheRebuildExecutor.class, () -> new ShopCacheRebuildExecutor(
                    1, 1, 60, context.getBean(ShopCacheMetrics.class)));
            context.register(CacheClient.class);
            context.refresh();

            assertNotNull(context.getBean(CacheClient.class));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shopLogicalCacheMetricsCountHitsMissesAndErrorsWithoutIdTags() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("cache:shop:1")).thenReturn("{\"id\":1}");
        when(values.get("cache:shop:2")).thenReturn("");
        when(values.get("cache:shop:3")).thenReturn(null);
        when(values.setIfAbsent(eq("lock:shop:3"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        Shop shop = new Shop();
        shop.setId(3L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheClient cache = new CacheClient(redis, new ShopCacheMetrics(registry));

        cache.queryWithLogicalExpire("cache:shop:", 1L, Shop.class, id -> null, 30L, TimeUnit.MINUTES);
        cache.queryWithLogicalExpire("cache:shop:", 2L, Shop.class, id -> null, 30L, TimeUnit.MINUTES);
        cache.queryWithLogicalExpire("cache:shop:", 3L, Shop.class, id -> shop, 30L, TimeUnit.MINUTES);

        assertEquals(1.0, registry.get("cityai.shop.cache.requests").tag("result", "value_hit").counter().count());
        assertEquals(1.0, registry.get("cityai.shop.cache.requests").tag("result", "empty_hit").counter().count());
        assertEquals(1.0, registry.get("cityai.shop.cache.requests").tag("result", "miss").counter().count());
        assertEquals(0, registry.get("cityai.shop.cache.requests").meters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .filter(tag -> tag.getKey().equals("id")).count());

        when(values.get("cache:shop:4")).thenThrow(new IllegalStateException("redis unavailable"));
        assertThrows(IllegalStateException.class, () -> cache.queryWithLogicalExpire(
                "cache:shop:", 4L, Shop.class, id -> null, 30L, TimeUnit.MINUTES));
        assertEquals(1.0, registry.get("cityai.shop.cache.requests").tag("result", "error").counter().count());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cacheHitSkipsCacheMissGuard() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("cache:shop:1")).thenReturn("{\"id\":1}");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheClient cache = new CacheClient(redis, new ShopCacheMetrics(registry));

        Shop result = cache.queryWithLogicalExpire("cache:shop:", 1L, Shop.class,
                id -> { throw new AssertionError("cache hit must not reach database"); },
                id -> { throw new AssertionError("cache hit must skip Bloom filter"); },
                30L, TimeUnit.MINUTES);

        assertEquals(1L, result.getId());
        assertEquals(1.0, registry.get("cityai.shop.cache.requests")
                .tag("result", "value_hit").counter().count());
        verify(values, never()).setIfAbsent(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectedCacheMissSkipsDatabaseAndDoesNotCountAsMiss() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("cache:shop:404")).thenReturn(null);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheClient cache = new CacheClient(redis, new ShopCacheMetrics(registry));

        Shop result = cache.queryWithLogicalExpire("cache:shop:", 404L, Shop.class,
                id -> { throw new AssertionError("Bloom rejection must skip database"); },
                id -> false, 30L, TimeUnit.MINUTES);

        assertNull(result);
        assertEquals(0.0, registry.get("cityai.shop.cache.requests")
                .tag("result", "miss").counter().count());
        verify(values, never()).setIfAbsent(anyString(), anyString(), anyLong(), any());
        verify(values, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectedBackgroundRefreshKeepsServingStaleShopAndReleasesLock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("cache:shop:9")).thenReturn(
                "{\"data\":{\"id\":9},\"expireTime\":\"2020-01-01T00:00:00\"}");
        when(values.setIfAbsent(eq("lock:shop:9"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheClient cache = new CacheClient(redis, new ShopCacheMetrics(registry),
                command -> { throw new java.util.concurrent.RejectedExecutionException("queue full"); });

        Shop result = cache.queryWithLogicalExpire("cache:shop:", 9L, Shop.class,
                id -> { throw new AssertionError("refresh should not run inline"); }, 30L, TimeUnit.MINUTES);

        assertEquals(9L, result.getId());
        assertEquals(1.0, registry.get("cityai.shop.cache.refresh.rejections").counter().count());
        verify(redis).execute(any(), eq(java.util.List.of("lock:shop:9")), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void lockAcquisitionFailureKeepsServingReadableStaleShop() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("cache:shop:13")).thenReturn(
                "{\"data\":{\"id\":13},\"expireTime\":\"2000-01-01T00:00:00\","
                        + "\"expireAtEpochMillis\":1}");
        when(values.setIfAbsent(eq("lock:shop:13"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenThrow(new IllegalStateException("redis lock unavailable"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheClient cache = new CacheClient(redis, new ShopCacheMetrics(registry));

        Shop stale = cache.queryWithLogicalExpire("cache:shop:", 13L, Shop.class,
                id -> { throw new AssertionError("failed lock acquisition must not load inline"); },
                30L, TimeUnit.MINUTES);

        assertEquals(13L, stale.getId());
        assertEquals(1.0, registry.get("cityai.shop.cache.requests")
                .tag("result", "error").counter().count());
        verify(redis, never()).execute(any(), eq(java.util.List.of("lock:shop:13")), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void logicalExpireLoadsColdShopAndKeepsPhysicalExpiry() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq("lock:shop:1"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        Shop shop = new Shop();
        shop.setId(1L);

        Shop loaded = new CacheClient(redis, new ShopCacheMetrics(new SimpleMeterRegistry())).queryWithLogicalExpire("cache:shop:", 1L,
                Shop.class, id -> shop, 30L, TimeUnit.MINUTES);

        assertEquals(1L, loaded.getId());
        verify(values).setIfAbsent(eq("cache:shop:1"), argThat(value ->
                value.contains("expireAtEpochMillis") && value.contains("expireTime")),
                eq(3600L), eq(TimeUnit.SECONDS));
        verify(redis).execute(any(), eq(java.util.List.of("lock:shop:1")), anyString());
    }

    @Test
    void logicalExpiryUsesAbsoluteTimestampAcrossInstanceTimeZones() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        long expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30);
        when(values.get("cache:shop:11")).thenReturn(
                "{\"data\":{\"id\":11},\"expireTime\":\"2000-01-01T00:00:00\","
                        + "\"expireAtEpochMillis\":" + expiresAt + "}");

        Shop cached = new CacheClient(redis, new ShopCacheMetrics(new SimpleMeterRegistry()))
                .queryWithLogicalExpire("cache:shop:", 11L, Shop.class,
                        id -> { throw new AssertionError("valid absolute expiration must not trigger refresh"); },
                        30L, TimeUnit.MINUTES);

        assertEquals(11L, cached.getId());
        verify(values, never()).setIfAbsent(eq("lock:shop:11"), anyString(), anyLong(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void logicalExpireCachesMissingShopBriefly() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq("lock:shop:1"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        new CacheClient(redis, new ShopCacheMetrics(new SimpleMeterRegistry())).queryWithLogicalExpire("cache:shop:", 1L, Shop.class,
                id -> null, 30L, TimeUnit.MINUTES);

        verify(values).setIfAbsent("cache:shop:1", "", 2L, TimeUnit.MINUTES);
    }

    @Test
    @SuppressWarnings("unchecked")
    void coldLoadReturnsNewerCacheWhenConcurrentUpdateWins() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("cache:shop:1")).thenReturn(null, null, "{\"id\":42}");
        when(values.setIfAbsent(eq("lock:shop:1"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(values.setIfAbsent("cache:shop:1", "", 2L, TimeUnit.MINUTES))
                .thenReturn(false);

        Shop result = new CacheClient(redis, new ShopCacheMetrics(new SimpleMeterRegistry()))
                .queryWithLogicalExpire("cache:shop:", 1L, Shop.class, id -> null, 30L, TimeUnit.MINUTES);

        assertEquals(42L, result.getId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void logicalExpireReadsLegacyShopUntilItsOriginalTtlEnds() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("cache:shop:1")).thenReturn("{\"id\":1}");

        Shop loaded = new CacheClient(redis, new ShopCacheMetrics(new SimpleMeterRegistry())).queryWithLogicalExpire("cache:shop:", 1L,
                Shop.class, id -> { throw new AssertionError("unexpected database query"); },
                30L, TimeUnit.MINUTES);

        assertEquals(1L, loaded.getId());
        verify(values, never()).setIfAbsent(eq("lock:shop:1"), anyString(), anyLong(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void expiredShopRefreshesInBackground() throws InterruptedException {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("cache:shop:1")).thenReturn(
                "{\"data\":{\"id\":1},\"expireTime\":\"2020-01-01T00:00:00\"}");
        when(values.setIfAbsent(eq("lock:shop:1"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        CountDownLatch refreshed = new CountDownLatch(1);
        ExecutorService rebuildExecutor = Executors.newSingleThreadExecutor();
        try {
            Shop stale = new CacheClient(redis, new ShopCacheMetrics(new SimpleMeterRegistry()), rebuildExecutor)
                    .queryWithLogicalExpire("cache:shop:", 1L, Shop.class, id -> {
                        refreshed.countDown();
                        Shop latest = new Shop();
                        latest.setId(id);
                        return latest;
                    }, 30L, TimeUnit.MINUTES);

            assertEquals(1L, stale.getId());
            assertTrue(refreshed.await(2, TimeUnit.SECONDS));
            verify(redis, timeout(2000)).execute(any(), eq(java.util.List.of("cache:shop:1")),
                    contains("expireTime"), contains("expireTime"), eq("3600"));
        } finally {
            rebuildExecutor.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void mutexRechecksCacheAfterAcquiringLock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("cache:shop:8")).thenReturn(null, "{\"id\":8}");
        when(values.setIfAbsent(eq("lock:shop:8"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        CacheClient cache = new CacheClient(redis, new ShopCacheMetrics(new SimpleMeterRegistry()));
        Shop result = cache.queryWithMutex("cache:shop:", 8L, Shop.class,
                id -> { throw new AssertionError("the cache was filled before lock acquisition"); },
                30L, TimeUnit.MINUTES);

        assertEquals(8L, result.getId());
        verify(redis).execute(any(), eq(java.util.List.of("lock:shop:8")), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void mutexRenewsOwnedLockDuringSlowDatabaseLoadAndStopsAfterward() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("cache:shop:12")).thenReturn(null);
        when(values.setIfAbsent(eq("lock:shop:12"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(redis.execute(any(), eq(java.util.List.of("lock:shop:12")), anyString(), eq("10")))
                .thenReturn(1L);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
        AtomicReference<Runnable> heartbeat = new AtomicReference<>();
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), eq(3L), eq(3L), eq(TimeUnit.SECONDS)))
                .thenAnswer(invocation -> {
                    heartbeat.set(invocation.getArgument(0));
                    return renewal;
                });
        Shop shop = new Shop();
        shop.setId(12L);

        Shop loaded = new CacheClient(redis, new ShopCacheMetrics(new SimpleMeterRegistry()), Runnable::run,
                scheduler).queryWithMutex("cache:shop:", 12L, Shop.class, id -> {
                    heartbeat.get().run();
                    return shop;
                }, 30L, TimeUnit.MINUTES);

        assertEquals(12L, loaded.getId());
        verify(redis).execute(any(), eq(java.util.List.of("lock:shop:12")), anyString(), eq("10"));
        verify(renewal).cancel(false);
        verify(redis).execute(any(), eq(java.util.List.of("lock:shop:12")), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void mutexRetryDoesNotReleaseAnotherRequestLock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("cache:shop:1")).thenReturn(null, "{\"id\":1}");
        when(values.setIfAbsent(eq("lock:shop:1"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        CacheClient cache = new CacheClient(redis, new ShopCacheMetrics(new SimpleMeterRegistry()));
        assertEquals(1L, cache.queryWithMutex("cache:shop:", 1L, Shop.class,
                id -> new Shop(), 30L, TimeUnit.MINUTES).getId());
        verify(redis, never()).delete("lock:shop:1");
    }
}

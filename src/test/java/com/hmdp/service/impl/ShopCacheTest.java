package com.hmdp.service.impl;

import com.hmdp.entity.Shop;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.ShopCacheMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopCacheTest {

    @Test
    @SuppressWarnings("unchecked")
    void detailUsesLogicalExpiryCache() {
        ShopServiceImpl service = spy(new ShopServiceImpl());
        CacheClient cache = mock(CacheClient.class);
        RBloomFilter<Long> bloom = mock(RBloomFilter.class);
        ReflectionTestUtils.setField(service, "cacheClient", cache);
        ReflectionTestUtils.setField(service, "shopBloomFilter", bloom);
        Shop shop = new Shop();
        shop.setId(1L);
        when(cache.queryWithLogicalExpire(eq("cache:shop:"), eq(1L), eq(Shop.class),
                any(), any(), eq(30L), eq(TimeUnit.MINUTES))).thenReturn(shop);

        assertEquals(shop, service.queryById(1L).getData());
        verify(cache).queryWithLogicalExpire(eq("cache:shop:"), eq(1L), eq(Shop.class),
                any(), any(), eq(30L), eq(TimeUnit.MINUTES));
        verifyNoInteractions(bloom);
    }

    @Test
    void bloomRejectionIsCountedSeparatelyFromCacheQueries() {
        ShopServiceImpl service = new ShopServiceImpl();
        RBloomFilter<Long> bloom = mock(RBloomFilter.class);
        CacheClient cache = mock(CacheClient.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(service, "shopBloomFilter", bloom);
        ReflectionTestUtils.setField(service, "cacheClient", cache);
        ReflectionTestUtils.setField(service, "shopCacheMetrics", new ShopCacheMetrics(registry));
        when(bloom.contains(404L)).thenReturn(false);
        when(cache.queryWithLogicalExpire(eq("cache:shop:"), eq(404L), eq(Shop.class),
                any(), any(), eq(30L), eq(TimeUnit.MINUTES))).thenAnswer(invocation -> {
                    java.util.function.Predicate<Long> guard = invocation.getArgument(4);
                    return guard.test(404L) ? new Shop() : null;
                });

        assertFalse(service.queryById(404L).getSuccess());

        assertEquals(1.0, registry.get("cityai.shop.cache.requests")
                .tag("result", "bloom_reject").counter().count());
        assertEquals(0.0, registry.get("cityai.shop.cache.requests")
                .tag("result", "miss").counter().count());
        verify(cache).queryWithLogicalExpire(eq("cache:shop:"), eq(404L), eq(Shop.class),
                any(), any(), eq(30L), eq(TimeUnit.MINUTES));
    }

    @Test
    void updateRefreshesCacheOnlyAfterCommit() {
        ShopServiceImpl service = spy(new ShopServiceImpl());
        CacheClient cache = mock(CacheClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        GeoOperations<String, String> geo = mock(GeoOperations.class);
        ReflectionTestUtils.setField(service, "cacheClient", cache);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        when(redis.opsForGeo()).thenReturn(geo);
        Shop patch = new Shop();
        patch.setId(1L);
        Shop latest = new Shop();
        latest.setId(1L).setTypeId(2L).setX(121.0).setY(31.0);
        Shop previous = new Shop().setId(1L).setTypeId(2L).setX(120.0).setY(30.0);
        doReturn(previous).when(service).getById(1L);
        doReturn(true).when(service).updateById(patch);
        doReturn(previous, latest).when(service).getById(1L);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.update(patch);
            verify(cache, never()).setWithLogicalExpire(anyString(), any(), anyLong(), any());
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(cache).setWithLogicalExpire("cache:shop:1", latest, 30L, TimeUnit.MINUTES);
            verify(geo).add("shop:geo:2", new org.springframework.data.geo.Point(121.0, 31.0), "1");
            verify(redis, never()).delete("cache:shop:1");
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void cacheRefreshFailureAfterCommitDoesNotTurnDatabaseUpdateIntoFailure() {
        ShopServiceImpl service = spy(new ShopServiceImpl());
        CacheClient cache = mock(CacheClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        GeoOperations<String, String> geo = mock(GeoOperations.class);
        ReflectionTestUtils.setField(service, "cacheClient", cache);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        when(redis.opsForGeo()).thenReturn(geo);
        Shop patch = new Shop().setId(1L);
        Shop latest = new Shop().setId(1L).setTypeId(2L).setX(121.0).setY(31.0);
        Shop previous = new Shop().setId(1L).setTypeId(1L).setX(120.0).setY(30.0);
        doReturn(true).when(service).updateById(patch);
        doReturn(previous, latest).when(service).getById(1L);
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(cache).setWithLogicalExpire("cache:shop:1", latest, 30L, TimeUnit.MINUTES);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertTrue(service.update(patch).getSuccess());
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                assertDoesNotThrow(sync::afterCommit);
            }
            verify(redis).delete("cache:shop:1");
            verify(geo).remove("shop:geo:1", "1");
            verify(geo).add("shop:geo:2", new org.springframework.data.geo.Point(121.0, 31.0), "1");
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void geoRefreshFailureAfterCommitIsLoggedAndRetriedWithoutReportingCommittedWriteAsFailed() {
        ShopServiceImpl service = spy(new ShopServiceImpl());
        CacheClient cache = mock(CacheClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        GeoOperations<String, String> geo = mock(GeoOperations.class);
        ReflectionTestUtils.setField(service, "cacheClient", cache);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        when(redis.opsForGeo()).thenReturn(geo);
        Shop patch = new Shop().setId(1L);
        Shop latest = new Shop().setId(1L).setTypeId(2L).setX(121.0).setY(31.0);
        doReturn(true).when(service).updateById(patch);
        doReturn(latest).when(service).getById(1L);
        when(geo.add("shop:geo:2", new org.springframework.data.geo.Point(121.0, 31.0), "1"))
                .thenThrow(new IllegalStateException("Redis unavailable"))
                .thenReturn(1L);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertTrue(service.update(patch).getSuccess());
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                assertDoesNotThrow(sync::afterCommit);
            }
            service.retryPendingGeoSync();
            verify(geo, times(2)).add("shop:geo:2", new org.springframework.data.geo.Point(121.0, 31.0), "1");
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void newShopReplacesConcurrentEmptyCacheAfterCommit() {
        ShopServiceImpl service = spy(new ShopServiceImpl());
        RBloomFilter<Long> bloom = mock(RBloomFilter.class);
        CacheClient cache = mock(CacheClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        GeoOperations<String, String> geo = mock(GeoOperations.class);
        ReflectionTestUtils.setField(service, "shopBloomFilter", bloom);
        ReflectionTestUtils.setField(service, "cacheClient", cache);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        when(redis.opsForGeo()).thenReturn(geo);
        Shop shop = new Shop();
        shop.setId(42L).setTypeId(2L).setX(121.0).setY(31.0);
        Shop latest = new Shop();
        latest.setId(42L).setTypeId(2L).setX(121.0).setY(31.0);
        doReturn(true).when(service).save(shop);
        doReturn(latest).when(service).getById(42L);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.saveShop(shop);
            verify(bloom, never()).add(42L);
            verify(cache, never()).setWithLogicalExpire(anyString(), any(), anyLong(), any());
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(bloom).add(42L);
            verify(cache).setWithLogicalExpire("cache:shop:42", latest, 30L, TimeUnit.MINUTES);
            verify(geo).add("shop:geo:2", new org.springframework.data.geo.Point(121.0, 31.0), "42");
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedShopInsertDoesNotReportSuccessOrPublishBloomId() {
        ShopServiceImpl service = spy(new ShopServiceImpl());
        RBloomFilter<Long> bloom = mock(RBloomFilter.class);
        ReflectionTestUtils.setField(service, "shopBloomFilter", bloom);
        Shop shop = new Shop();
        shop.setId(43L);
        doReturn(false).when(service).save(shop);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertFalse(service.saveShop(shop).getSuccess());
            verifyNoInteractions(bloom);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedBloomPublishAfterCommitIsRetried() {
        ShopServiceImpl service = spy(new ShopServiceImpl());
        RBloomFilter<Long> bloom = mock(RBloomFilter.class);
        CacheClient cache = mock(CacheClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        GeoOperations<String, String> geo = mock(GeoOperations.class);
        ReflectionTestUtils.setField(service, "shopBloomFilter", bloom);
        ReflectionTestUtils.setField(service, "cacheClient", cache);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        when(redis.opsForGeo()).thenReturn(geo);
        Shop shop = new Shop();
        shop.setId(44L).setTypeId(2L).setX(121.0).setY(31.0);
        doReturn(true).when(service).save(shop);
        doReturn(shop).when(service).getById(44L);
        when(bloom.add(44L)).thenThrow(new IllegalStateException("Redis unavailable")).thenReturn(true);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.saveShop(shop);
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(bloom).add(44L);

            service.retryPendingBloomAdds();

            verify(bloom, times(2)).add(44L);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void transactionRollbackDoesNotPublishShopToSharedRedisIndexes() {
        ShopServiceImpl service = spy(new ShopServiceImpl());
        RBloomFilter<Long> bloom = mock(RBloomFilter.class);
        CacheClient cache = mock(CacheClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "shopBloomFilter", bloom);
        ReflectionTestUtils.setField(service, "cacheClient", cache);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        Shop shop = new Shop();
        shop.setId(45L);
        doReturn(true).when(service).save(shop);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.saveShop(shop);
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            verifyNoInteractions(bloom, cache, redis);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }
}

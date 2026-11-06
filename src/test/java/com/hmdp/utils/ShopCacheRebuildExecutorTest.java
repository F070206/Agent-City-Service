package com.hmdp.utils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCacheRebuildExecutorTest {
    @Test
    void reportsWorkerAndQueueSaturation() throws InterruptedException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ShopCacheMetrics metrics = new ShopCacheMetrics(registry);
        ShopCacheRebuildExecutor rebuildPool = new ShopCacheRebuildExecutor(1, 1, 60, metrics);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            rebuildPool.executor().execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            rebuildPool.executor().execute(() -> { });

            assertEquals(1, registry.get("cityai.shop.cache.rebuild.active").gauge().value());
            assertEquals(1, registry.get("cityai.shop.cache.rebuild.pool.size").gauge().value());
            assertEquals(1, registry.get("cityai.shop.cache.rebuild.queued").gauge().value());
            assertEquals(0, registry.get("cityai.shop.cache.rebuild.queue.remaining").gauge().value());
        } finally {
            release.countDown();
            rebuildPool.shutdown();
            registry.close();
        }
    }

    @Test
    void rejectsInvalidCapacityConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new ShopCacheRebuildExecutor(0, 1, 60, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ShopCacheRebuildExecutor(1, 0, 60, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ShopCacheRebuildExecutor(1, 1, -1, null));
    }
}

package com.hmdp.utils;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded worker pool for refreshing logically expired shop cache entries. */
@Component
public class ShopCacheRebuildExecutor {
    private final ThreadPoolExecutor executor;

    public ShopCacheRebuildExecutor(
            @Value("${shop.cache.rebuild.threads:10}") int threads,
            @Value("${shop.cache.rebuild.queue-capacity:1000}") int queueCapacity,
            @Value("${shop.cache.rebuild.keep-alive-seconds:60}") int keepAliveSeconds,
            ShopCacheMetrics metrics) {
        if (threads < 1 || queueCapacity < 1 || keepAliveSeconds < 0) {
            throw new IllegalArgumentException("缓存重建线程数和队列容量必须为正数，空闲超时不能为负数");
        }
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "shop-cache-rebuild");
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(threads, threads, keepAliveSeconds, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), threadFactory, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(keepAliveSeconds > 0);
        metrics.registerRebuildExecutor(executor);
    }

    ThreadPoolExecutor executor() {
        return executor;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}

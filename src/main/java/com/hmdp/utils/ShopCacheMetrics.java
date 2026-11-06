package com.hmdp.utils;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;

/** Low-cardinality metrics for the shop cache path and rebuild pool. */
@Component
public class ShopCacheMetrics {
    private final MeterRegistry meterRegistry;
    private final Counter valueHits;
    private final Counter emptyHits;
    private final Counter misses;
    private final Counter bloomRejections;
    private final Counter errors;
    private final Counter refreshRejections;

    public ShopCacheMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        valueHits = counter(meterRegistry, "value_hit");
        emptyHits = counter(meterRegistry, "empty_hit");
        misses = counter(meterRegistry, "miss");
        bloomRejections = counter(meterRegistry, "bloom_reject");
        errors = counter(meterRegistry, "error");
        refreshRejections = Counter.builder("cityai.shop.cache.refresh.rejections")
                .description("Shop cache background refresh tasks rejected by the bounded executor")
                .register(meterRegistry);
    }

    private Counter counter(MeterRegistry registry, String result) {
        return Counter.builder("cityai.shop.cache.requests")
                .tag("result", result)
                .description("Shop cache request outcomes")
                .register(registry);
    }

    public void recordValueHit() {
        valueHits.increment();
    }

    public void recordEmptyHit() {
        emptyHits.increment();
    }

    public void recordMiss() {
        misses.increment();
    }

    public void recordBloomRejection() {
        bloomRejections.increment();
    }

    public void recordError() {
        errors.increment();
    }

    public void recordRefreshRejection() {
        refreshRejections.increment();
    }

    public void registerRebuildExecutor(ThreadPoolExecutor executor) {
        Gauge.builder("cityai.shop.cache.rebuild.active", executor, ThreadPoolExecutor::getActiveCount)
                .description("Shop cache refresh worker threads currently rebuilding values")
                .register(meterRegistry);
        Gauge.builder("cityai.shop.cache.rebuild.queued", executor,
                        pool -> pool.getQueue().size())
                .description("Shop cache refresh tasks waiting in the bounded queue")
                .register(meterRegistry);
        Gauge.builder("cityai.shop.cache.rebuild.queue.remaining", executor,
                        pool -> pool.getQueue().remainingCapacity())
                .description("Remaining capacity of the shop cache refresh queue")
                .register(meterRegistry);
        Gauge.builder("cityai.shop.cache.rebuild.pool.size", executor,
                        ThreadPoolExecutor::getPoolSize)
                .description("Current shop cache refresh worker pool size")
                .register(meterRegistry);
    }
}

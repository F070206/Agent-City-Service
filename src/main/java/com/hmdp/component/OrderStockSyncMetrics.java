package com.hmdp.component;

import com.hmdp.entity.OrderStockSyncBacklog;
import com.hmdp.mapper.OrderStockSyncMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OrderStockSyncMetrics {
    private final OrderStockSyncMapper taskMapper;
    private final MeterRegistry meterRegistry;
    @Value("${order.stock-sync.metrics.repeated-failure-threshold:3}")
    private int repeatedFailureThreshold = 3;
    private volatile double pendingCount = Double.NaN;
    private volatile double oldestPendingAgeSeconds = Double.NaN;
    private volatile double repeatedFailureCount = Double.NaN;

    public OrderStockSyncMetrics(OrderStockSyncMapper taskMapper, MeterRegistry meterRegistry) {
        this.taskMapper = taskMapper;
        this.meterRegistry = meterRegistry;
        Gauge.builder("cityai.order.stock.sync.pending", this, metrics -> metrics.pendingCount)
                .description("Uncompleted order stock synchronization tasks")
                .register(meterRegistry);
        Gauge.builder("cityai.order.stock.sync.oldest.age", this, metrics -> metrics.oldestPendingAgeSeconds)
                .description("Age in seconds of the oldest uncompleted order stock synchronization task")
                .register(meterRegistry);
        Gauge.builder("cityai.order.stock.sync.repeated.failures", this, metrics -> metrics.repeatedFailureCount)
                .description("Uncompleted order stock synchronization tasks with repeated failures")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${order.stock-sync.metrics.refresh-ms:30000}")
    void refreshSnapshot() {
        try {
            OrderStockSyncBacklog snapshot = taskMapper.getBacklogSnapshot(Math.max(1, repeatedFailureThreshold));
            if (snapshot == null) {
                return;
            }
            pendingCount = Math.max(0, snapshot.getPendingCount());
            oldestPendingAgeSeconds = Math.max(0, snapshot.getOldestPendingAgeSeconds());
            repeatedFailureCount = Math.max(0, snapshot.getRepeatedFailureCount());
        } catch (Exception e) {
            log.warn("刷新订单库存同步积压指标失败，继续使用上次快照", e);
        }
    }
}

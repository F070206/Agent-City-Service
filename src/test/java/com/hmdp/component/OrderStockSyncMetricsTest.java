package com.hmdp.component;

import com.hmdp.entity.OrderStockSyncBacklog;
import com.hmdp.mapper.OrderStockSyncMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class OrderStockSyncMetricsTest {

    @Test
    void publishesPendingCountAndOldestAgeFromSnapshot() {
        OrderStockSyncMapper mapper = mock(OrderStockSyncMapper.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(mapper.getBacklogSnapshot(anyInt())).thenReturn(snapshot(12, 95, 4));
        OrderStockSyncMetrics metrics = new OrderStockSyncMetrics(mapper, registry);

        metrics.refreshSnapshot();

        assertEquals(12, registry.get("cityai.order.stock.sync.pending").gauge().value());
        assertEquals(95, registry.get("cityai.order.stock.sync.oldest.age").gauge().value());
        assertEquals(4, registry.get("cityai.order.stock.sync.repeated.failures").gauge().value());
    }

    @Test
    void keepsLastSnapshotWhenRefreshFailsOrReturnsNull() {
        OrderStockSyncMapper mapper = mock(OrderStockSyncMapper.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(mapper.getBacklogSnapshot(anyInt())).thenReturn(snapshot(3, 40, 2), null)
                .thenThrow(new IllegalStateException("database unavailable"));
        OrderStockSyncMetrics metrics = new OrderStockSyncMetrics(mapper, registry);

        metrics.refreshSnapshot();
        metrics.refreshSnapshot();

        assertEquals(3, registry.get("cityai.order.stock.sync.pending").gauge().value());
        assertEquals(40, registry.get("cityai.order.stock.sync.oldest.age").gauge().value());
        assertEquals(2, registry.get("cityai.order.stock.sync.repeated.failures").gauge().value());
    }

    @Test
    void leavesMetricsUnavailableUntilTheFirstDatabaseSnapshot() {
        OrderStockSyncMapper mapper = mock(OrderStockSyncMapper.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(mapper.getBacklogSnapshot(anyInt())).thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(snapshot(0, 0, 0));
        OrderStockSyncMetrics metrics = new OrderStockSyncMetrics(mapper, registry);

        assertEquals(Double.NaN, registry.get("cityai.order.stock.sync.pending").gauge().value());
        metrics.refreshSnapshot();
        metrics.refreshSnapshot();

        assertEquals(0, registry.get("cityai.order.stock.sync.pending").gauge().value());
        assertEquals(0, registry.get("cityai.order.stock.sync.oldest.age").gauge().value());
        assertEquals(0, registry.get("cityai.order.stock.sync.repeated.failures").gauge().value());
    }

    private OrderStockSyncBacklog snapshot(long pending, long ageSeconds, long repeatedFailures) {
        OrderStockSyncBacklog snapshot = new OrderStockSyncBacklog();
        snapshot.setPendingCount(pending);
        snapshot.setOldestPendingAgeSeconds(ageSeconds);
        snapshot.setRepeatedFailureCount(repeatedFailures);
        return snapshot;
    }
}

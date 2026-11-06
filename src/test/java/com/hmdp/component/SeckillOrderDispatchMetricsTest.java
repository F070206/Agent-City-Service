package com.hmdp.component;

import com.hmdp.entity.SeckillOrderDispatchBacklog;
import com.hmdp.mapper.SeckillOrderDispatchMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SeckillOrderDispatchMetricsTest {
    @Test
    void publishesOrderAndDueTimeoutBacklogFromOneSnapshot() {
        SeckillOrderDispatchMapper mapper = mock(SeckillOrderDispatchMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.zCard("seckill:dispatch:recovery")).thenReturn(7L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SeckillOrderDispatchBacklog backlog = new SeckillOrderDispatchBacklog();
        backlog.setOrderPendingCount(12);
        backlog.setOrderOldestPendingAgeSeconds(95);
        backlog.setOrderRepeatedFailureCount(4);
        backlog.setTimeoutPendingCount(3);
        backlog.setTimeoutOldestPendingAgeSeconds(41);
        backlog.setTimeoutRepeatedFailureCount(2);
        when(mapper.getBacklogSnapshot(3)).thenReturn(backlog);

        SeckillOrderDispatchMetrics metrics = new SeckillOrderDispatchMetrics(mapper, redis, registry, 3);
        metrics.refreshSnapshot();

        assertEquals(12, registry.get("cityai.seckill.dispatch.orders.pending").gauge().value());
        assertEquals(95, registry.get("cityai.seckill.dispatch.orders.oldest.age").gauge().value());
        assertEquals(4, registry.get("cityai.seckill.dispatch.orders.repeated.failures").gauge().value());
        assertEquals(3, registry.get("cityai.seckill.dispatch.timeouts.pending").gauge().value());
        assertEquals(41, registry.get("cityai.seckill.dispatch.timeouts.oldest.age").gauge().value());
        assertEquals(2, registry.get("cityai.seckill.dispatch.timeouts.repeated.failures").gauge().value());
        assertEquals(7, registry.get("cityai.seckill.dispatch.redis.recovery.pending").gauge().value());
    }

    @Test
    void keepsTheLastSnapshotIfDatabaseRefreshFails() {
        SeckillOrderDispatchMapper mapper = mock(SeckillOrderDispatchMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.zCard("seckill:dispatch:recovery")).thenReturn(4L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SeckillOrderDispatchBacklog backlog = new SeckillOrderDispatchBacklog();
        backlog.setOrderPendingCount(2);
        backlog.setTimeoutPendingCount(1);
        when(mapper.getBacklogSnapshot(1)).thenReturn(backlog)
                .thenThrow(new IllegalStateException("database unavailable"));

        SeckillOrderDispatchMetrics metrics = new SeckillOrderDispatchMetrics(mapper, redis, registry, 0);
        metrics.refreshSnapshot();
        metrics.refreshSnapshot();

        assertEquals(2, registry.get("cityai.seckill.dispatch.orders.pending").gauge().value());
        assertEquals(1, registry.get("cityai.seckill.dispatch.timeouts.pending").gauge().value());
        assertEquals(4, registry.get("cityai.seckill.dispatch.redis.recovery.pending").gauge().value());
        verify(mapper, times(2)).getBacklogSnapshot(1);
    }
}

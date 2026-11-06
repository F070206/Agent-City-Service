package com.hmdp.component;

import com.hmdp.entity.OrderStockSync;
import com.hmdp.mapper.OrderStockSyncMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderStockSyncWorkerTest {

    private OrderStockSyncMapper tasks;
    private StringRedisTemplate redis;
    private OrderStockSyncWorker worker;
    private OrderStockSync first;

    @BeforeEach
    void setUp() {
        tasks = mock(OrderStockSyncMapper.class);
        redis = mock(StringRedisTemplate.class);
        worker = new OrderStockSyncWorker(tasks, redis);
        first = new OrderStockSync();
        first.setOrderId(1L);
        first.setVoucherId(10L);
        first.setUserId(7L);
        when(tasks.findClaimed(anyString())).thenReturn(List.of(first));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureKeepsTaskPendingForRetry() {
        when(redis.execute(any(RedisScript.class), anyList(), eq("7"), anyString()))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        worker.syncPending();
        verify(tasks).claimPending(anyString());
        verify(tasks, never()).markCompleted(anyLong(), anyString());
        verify(tasks).recordFailure(eq(1L), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void failureAfterRedisSuccessCanBeRetriedAsDuplicate() {
        when(redis.execute(any(RedisScript.class), anyList(), eq("7"), anyString())).thenReturn(1L, 0L);
        when(tasks.markCompleted(eq(1L), anyString())).thenThrow(new IllegalStateException("commit acknowledgement lost"))
                .thenReturn(1);
        worker.syncPending();
        worker.syncPending();
        verify(tasks, times(2)).markCompleted(eq(1L), anyString());
        verify(tasks).recordFailure(eq(1L), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingRedisDataMustNotBeMarkedCompleted() {
        when(redis.execute(any(RedisScript.class), anyList(), eq("7"), anyString())).thenReturn(-1L);
        worker.syncPending();
        verify(tasks, never()).markCompleted(anyLong(), anyString());
        verify(tasks).recordFailure(eq(1L), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingOrderIdentityMappingKeepsTaskPendingForReconciliation() {
        when(redis.execute(any(RedisScript.class), anyList(), eq("7"), anyString())).thenReturn(-3L);

        worker.syncPending();

        verify(tasks, never()).markCompleted(anyLong(), anyString());
        verify(tasks).recordFailure(eq(1L), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleWorkerCannotCompleteOrRetryTaskAfterLeaseTakeover() {
        when(redis.execute(any(RedisScript.class), anyList(), eq("7"), anyString())).thenReturn(1L);
        when(tasks.markCompleted(eq(1L), anyString())).thenReturn(0);

        worker.syncPending();

        verify(tasks).markCompleted(eq(1L), anyString());
        verify(tasks, never()).recordFailure(anyLong(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unavailableRetryDatabaseDoesNotLoseTaskOrStopLaterTasks() {
        OrderStockSync second = new OrderStockSync();
        second.setOrderId(2L);
        second.setVoucherId(10L);
        second.setUserId(8L);
        when(tasks.findClaimed(anyString())).thenReturn(List.of(first, second));
        when(redis.execute(any(RedisScript.class), anyList(), eq("7"), anyString())).thenReturn(-2L);
        when(redis.execute(any(RedisScript.class), anyList(), eq("8"), anyString())).thenReturn(1L);
        when(tasks.recordFailure(eq(1L), anyString())).thenThrow(new IllegalStateException("database unavailable"));
        worker.syncPending();
        verify(tasks, never()).markCompleted(eq(1L), anyString());
        verify(tasks).markCompleted(eq(2L), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseScriptReceivesReservationMapAndOrderIdentity() {
        when(redis.execute(any(RedisScript.class), anyList(), eq("7"), eq("1"))).thenReturn(0L);

        worker.syncPending();

        verify(redis).execute(any(RedisScript.class), eq(List.of("seckill:stock:10", "seckill:order:10",
                "seckill:stock:released:1", "seckill:order:pending:1", "seckill:order:reservation:10")),
                eq("7"), eq("1"));
        verify(tasks).markCompleted(eq(1L), anyString());
    }
}

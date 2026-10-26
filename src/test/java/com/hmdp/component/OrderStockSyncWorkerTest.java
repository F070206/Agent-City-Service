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

    @BeforeEach
    void setUp() {
        tasks = mock(OrderStockSyncMapper.class);
        redis = mock(StringRedisTemplate.class);
        worker = new OrderStockSyncWorker(tasks, redis);
        OrderStockSync task = new OrderStockSync();
        task.setOrderId(1L);
        task.setVoucherId(10L);
        task.setUserId(7L);
        when(tasks.findPending()).thenReturn(List.of(task));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureKeepsTaskPendingForRetry() {
        when(redis.execute(any(RedisScript.class), anyList(), eq("7")))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        worker.syncPending();
        verify(tasks, never()).markCompleted(anyLong());
        verify(tasks).recordFailure(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failureAfterRedisSuccessCanBeRetriedAsDuplicate() {
        when(redis.execute(any(RedisScript.class), anyList(), eq("7"))).thenReturn(1L, 0L);
        when(tasks.markCompleted(1L)).thenThrow(new IllegalStateException("commit acknowledgement lost"))
                .thenReturn(1);
        worker.syncPending();
        worker.syncPending();
        verify(tasks, times(2)).markCompleted(1L);
        verify(tasks).recordFailure(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingRedisDataMustNotBeMarkedCompleted() {
        when(redis.execute(any(RedisScript.class), anyList(), eq("7"))).thenReturn(-1L);
        worker.syncPending();
        verify(tasks, never()).markCompleted(anyLong());
        verify(tasks).recordFailure(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unavailableRetryDatabaseDoesNotLoseTaskOrStopLaterTasks() {
        OrderStockSync second = new OrderStockSync();
        second.setOrderId(2L);
        second.setVoucherId(10L);
        second.setUserId(8L);
        OrderStockSync first = tasks.findPending().get(0);
        when(tasks.findPending()).thenReturn(List.of(first, second));
        when(redis.execute(any(RedisScript.class), anyList(), eq("7"))).thenReturn(-2L);
        when(redis.execute(any(RedisScript.class), anyList(), eq("8"))).thenReturn(1L);
        when(tasks.recordFailure(1L)).thenThrow(new IllegalStateException("database unavailable"));
        worker.syncPending();
        verify(tasks, never()).markCompleted(1L);
        verify(tasks).markCompleted(2L);
    }
}

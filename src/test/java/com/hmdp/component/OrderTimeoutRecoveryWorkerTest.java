package com.hmdp.component;

import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.OrderTimeoutRetryMapper;
import com.hmdp.service.IVoucherOrderService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderTimeoutRecoveryWorkerTest {

    @Test
    void persistsScanFailureAndContinuesTheCurrentBatch() {
        VoucherOrderMapper mapper = mock(VoucherOrderMapper.class);
        OrderTimeoutRetryMapper retries = mock(OrderTimeoutRetryMapper.class);
        IVoucherOrderService orders = mock(IVoucherOrderService.class);
        when(mapper.findExpiredSeckillOrderIds(any(LocalDateTime.class), eq(0L), eq(100)))
                .thenReturn(List.of(1L, 2L));
        when(retries.findClaimed(anyString())).thenReturn(List.of());
        doThrow(new IllegalStateException("temporary failure")).doNothing()
                .when(orders).closeTimeoutOrder(1L);

        OrderTimeoutRecoveryWorker worker = new OrderTimeoutRecoveryWorker(mapper, orders, retries, lockClient(true));
        ReflectionTestUtils.setField(worker, "maxBatchesPerRun", 1);
        worker.closeExpiredOrders();
        verify(retries).scheduleRetry(1L);

        verify(orders).closeTimeoutOrder(1L);
        verify(orders).closeTimeoutOrder(2L);
    }

    @Test
    void capsPagesPerRunAndContinuesFromTheLastCursor() {
        VoucherOrderMapper mapper = mock(VoucherOrderMapper.class);
        OrderTimeoutRetryMapper retries = mock(OrderTimeoutRetryMapper.class);
        IVoucherOrderService orders = mock(IVoucherOrderService.class);
        when(mapper.findExpiredSeckillOrderIds(any(LocalDateTime.class), anyLong(), eq(100)))
                .thenAnswer(invocation -> {
                    long afterId = invocation.getArgument(1);
                    return afterId < 3 ? List.of(afterId + 1) : List.of();
                });

        OrderTimeoutRecoveryWorker worker = new OrderTimeoutRecoveryWorker(mapper, orders, retries, lockClient(true));
        ReflectionTestUtils.setField(worker, "maxBatchesPerRun", 2);
        worker.closeExpiredOrders();

        verify(orders).closeTimeoutOrder(1L);
        verify(orders).closeTimeoutOrder(2L);
        verify(orders, never()).closeTimeoutOrder(3L);
        verify(mapper, times(2)).findExpiredSeckillOrderIds(any(LocalDateTime.class), anyLong(), eq(100));

        worker.closeExpiredOrders();
        verify(orders).closeTimeoutOrder(3L);
    }

    @Test
    void skipsDuplicateScanWhenAnotherInstanceOwnsTheLock() {
        VoucherOrderMapper mapper = mock(VoucherOrderMapper.class);
        OrderTimeoutRetryMapper retries = mock(OrderTimeoutRetryMapper.class);
        IVoucherOrderService orders = mock(IVoucherOrderService.class);
        OrderTimeoutRecoveryWorker worker = new OrderTimeoutRecoveryWorker(mapper, orders, retries, lockClient(false));

        worker.closeExpiredOrders();

        verifyNoInteractions(mapper, orders);
        verifyNoInteractions(retries);
    }

    @Test
    void continuesDatabaseRecoveryWhenRedisLockIsUnavailable() {
        VoucherOrderMapper mapper = mock(VoucherOrderMapper.class);
        OrderTimeoutRetryMapper retries = mock(OrderTimeoutRetryMapper.class);
        IVoucherOrderService orders = mock(IVoucherOrderService.class);
        RedissonClient redis = mock(RedissonClient.class);
        when(redis.getLock("lock:order:timeout-recovery")).thenThrow(new IllegalStateException("redis down"));
        when(mapper.findExpiredSeckillOrderIds(any(LocalDateTime.class), eq(0L), eq(100)))
                .thenReturn(List.of());
        OrderTimeoutRecoveryWorker worker = new OrderTimeoutRecoveryWorker(mapper, orders, retries, redis);

        worker.closeExpiredOrders();

        verify(mapper).findExpiredSeckillOrderIds(any(LocalDateTime.class), eq(0L), eq(100));
    }

    @Test
    void retriesPersistedFailureAndDeletesItAfterOrderIsHandled() {
        VoucherOrderMapper mapper = mock(VoucherOrderMapper.class);
        OrderTimeoutRetryMapper retries = mock(OrderTimeoutRetryMapper.class);
        IVoucherOrderService orders = mock(IVoucherOrderService.class);
        when(mapper.findExpiredSeckillOrderIds(any(LocalDateTime.class), eq(0L), eq(100)))
                .thenReturn(List.of());
        com.hmdp.entity.OrderTimeoutRetry task = new com.hmdp.entity.OrderTimeoutRetry();
        task.setOrderId(9L);
        when(retries.findClaimed(anyString())).thenReturn(List.of(task));
        doThrow(new IllegalStateException("still unavailable"))
                .when(orders).closeTimeoutOrder(9L);
        OrderTimeoutRecoveryWorker worker = new OrderTimeoutRecoveryWorker(mapper, orders, retries, lockClient(true));

        worker.closeExpiredOrders();

        verify(retries).retryLater(eq(9L), anyString());
        verify(retries, never()).deleteClaimed(anyLong(), anyString());
    }

    @Test
    void deletesPersistedFailureAfterSuccessfulRetry() {
        VoucherOrderMapper mapper = mock(VoucherOrderMapper.class);
        OrderTimeoutRetryMapper retries = mock(OrderTimeoutRetryMapper.class);
        IVoucherOrderService orders = mock(IVoucherOrderService.class);
        when(mapper.findExpiredSeckillOrderIds(any(LocalDateTime.class), eq(0L), eq(100)))
                .thenReturn(List.of());
        com.hmdp.entity.OrderTimeoutRetry task = new com.hmdp.entity.OrderTimeoutRetry();
        task.setOrderId(10L);
        when(retries.findClaimed(anyString())).thenReturn(List.of(task));
        OrderTimeoutRecoveryWorker worker = new OrderTimeoutRecoveryWorker(mapper, orders, retries, lockClient(true));

        worker.closeExpiredOrders();

        verify(orders).closeTimeoutOrder(10L);
        verify(retries).deleteClaimed(eq(10L), anyString());
    }

    private RedissonClient lockClient(boolean acquired) {
        RedissonClient redis = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redis.getLock("lock:order:timeout-recovery")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(acquired);
        when(lock.isHeldByCurrentThread()).thenReturn(acquired);
        return redis;
    }
}

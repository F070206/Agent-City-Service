package com.hmdp.component;

import com.hmdp.config.RocketMQConstants;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.OrderTimeoutRetryMapper;
import com.hmdp.entity.OrderTimeoutRetry;
import com.hmdp.service.IVoucherOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutRecoveryWorker {

    private static final int BATCH_SIZE = 100;

    @Value("${order.timeout-recovery.max-batches-per-run:10}")
    private int maxBatchesPerRun = 10;

    @Value("${order.timeout-recovery.lock-name:lock:order:timeout-recovery}")
    private String lockName = "lock:order:timeout-recovery";

    private final VoucherOrderMapper orderMapper;
    private final IVoucherOrderService orderService;
    private final OrderTimeoutRetryMapper retryMapper;
    private final RedissonClient redissonClient;
    private long afterId;
    private LocalDateTime scanDeadline;
    private final Set<Long> retriedThisRun = new HashSet<>();

    @Scheduled(fixedDelayString = "${order.timeout-recovery.delay-ms:30000}")
    public synchronized void closeExpiredOrders() {
        RLock lock = null;
        boolean lockAcquired = false;
        try {
            try {
                lock = redissonClient.getLock(lockName);
                lockAcquired = lock.tryLock();
                if (!lockAcquired) {
                    return;
                }
            } catch (RuntimeException e) {
                // DB 状态更新仍保证正确性；Redis 锁异常时退化为重复扫描，而不是停止补扫。
                log.warn("获取订单超时补扫分布式锁失败，将依赖数据库状态条件继续补扫", e);
            }

            retriedThisRun.clear();
            scanExpiredOrders();
            retryPreviouslyFailedOrders();
        } finally {
            if (lockAcquired && lock != null) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (RuntimeException e) {
                    log.warn("释放订单超时补扫分布式锁失败", e);
                }
            }
        }
    }

    private void scanExpiredOrders() {
        if (scanDeadline == null) {
            scanDeadline = LocalDateTime.now().minusNanos(
                    RocketMQConstants.ORDER_PAYMENT_TIMEOUT_MILLIS * 1_000_000);
        }
        LocalDateTime deadline = scanDeadline;
        int batchLimit = Math.max(1, maxBatchesPerRun);
        for (int batch = 0; batch < batchLimit; batch++) {
            List<Long> ids = orderMapper.findExpiredSeckillOrderIds(deadline, afterId, BATCH_SIZE);
            if (ids.isEmpty()) {
                afterId = 0;
                scanDeadline = null;
                return;
            }
            for (Long id : ids) {
                afterId = id;
                if (retriedThisRun.contains(id)) {
                    continue;
                }
                try {
                    orderService.closeTimeoutOrder(id);
                } catch (Exception e) {
                    try {
                        retryMapper.scheduleRetry(id);
                    } catch (Exception retryError) {
                        e.addSuppressed(retryError);
                    }
                    log.error("补扫超时订单失败，下轮继续重试，订单ID={}", id, e);
                }
            }
        }
    }

    private void retryPreviouslyFailedOrders() {
        String token = UUID.randomUUID().toString();
        retryMapper.claimPending(token);
        for (OrderTimeoutRetry task : retryMapper.findClaimed(token)) {
            try {
                orderService.closeTimeoutOrder(task.getOrderId());
                retryMapper.deleteClaimed(task.getOrderId(), token);
                retriedThisRun.add(task.getOrderId());
            } catch (Exception e) {
                try {
                    retryMapper.retryLater(task.getOrderId(), token);
                } catch (Exception retryError) {
                    e.addSuppressed(retryError);
                }
                log.error("重试超时订单失败，将延后重试，订单ID={}", task.getOrderId(), e);
                retriedThisRun.add(task.getOrderId());
            }
        }
    }

}

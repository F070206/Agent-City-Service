package com.hmdp.component;

import com.hmdp.entity.SeckillOrderDispatchBacklog;
import com.hmdp.mapper.SeckillOrderDispatchMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import static com.hmdp.utils.RedisConstants.SECKILL_DISPATCH_RECOVERY_KEY;

@Slf4j
@Component
public class SeckillOrderDispatchMetrics {
    private final SeckillOrderDispatchMapper taskMapper;
    private final StringRedisTemplate redisTemplate;
    private final int repeatedFailureThreshold;
    private volatile double orderPendingCount = Double.NaN;
    private volatile double orderOldestPendingAgeSeconds = Double.NaN;
    private volatile double orderRepeatedFailureCount = Double.NaN;
    private volatile double timeoutPendingCount = Double.NaN;
    private volatile double timeoutOldestPendingAgeSeconds = Double.NaN;
    private volatile double timeoutRepeatedFailureCount = Double.NaN;
    private volatile double redisRecoveryPendingCount = Double.NaN;

    public SeckillOrderDispatchMetrics(SeckillOrderDispatchMapper taskMapper, StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry,
            @Value("${order.dispatch.metrics.repeated-failure-threshold:3}") int repeatedFailureThreshold) {
        this.taskMapper = taskMapper;
        this.redisTemplate = redisTemplate;
        this.repeatedFailureThreshold = Math.max(1, repeatedFailureThreshold);
        registerGauge(meterRegistry, "cityai.seckill.dispatch.orders.pending", "Uncompleted seckill order dispatch tasks",
                () -> orderPendingCount);
        registerGauge(meterRegistry, "cityai.seckill.dispatch.orders.oldest.age",
                "Age in seconds of the oldest uncompleted seckill order dispatch task",
                () -> orderOldestPendingAgeSeconds);
        registerGauge(meterRegistry, "cityai.seckill.dispatch.orders.repeated.failures",
                "Uncompleted seckill order dispatch tasks with repeated send attempts",
                () -> orderRepeatedFailureCount);
        registerGauge(meterRegistry, "cityai.seckill.dispatch.timeouts.pending",
                "Due timeout dispatch tasks that have not been sent", () -> timeoutPendingCount);
        registerGauge(meterRegistry, "cityai.seckill.dispatch.timeouts.oldest.age",
                "Age in seconds of the oldest due timeout dispatch task", () -> timeoutOldestPendingAgeSeconds);
        registerGauge(meterRegistry, "cityai.seckill.dispatch.timeouts.repeated.failures",
                "Due timeout dispatch tasks with repeated send failures", () -> timeoutRepeatedFailureCount);
        registerGauge(meterRegistry, "cityai.seckill.dispatch.redis.recovery.pending",
                "Reservations waiting to be persisted from the Redis recovery queue",
                () -> redisRecoveryPendingCount);
    }

    private void registerGauge(MeterRegistry registry, String name, String description,
            java.util.function.DoubleSupplier value) {
        Gauge.builder(name, value, java.util.function.DoubleSupplier::getAsDouble)
                .description(description)
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${order.dispatch.metrics.refresh-ms:30000}")
    void refreshSnapshot() {
        try {
            SeckillOrderDispatchBacklog snapshot = taskMapper.getBacklogSnapshot(repeatedFailureThreshold);
            if (snapshot != null) {
                orderPendingCount = Math.max(0, snapshot.getOrderPendingCount());
                orderOldestPendingAgeSeconds = Math.max(0, snapshot.getOrderOldestPendingAgeSeconds());
                orderRepeatedFailureCount = Math.max(0, snapshot.getOrderRepeatedFailureCount());
                timeoutPendingCount = Math.max(0, snapshot.getTimeoutPendingCount());
                timeoutOldestPendingAgeSeconds = Math.max(0, snapshot.getTimeoutOldestPendingAgeSeconds());
                timeoutRepeatedFailureCount = Math.max(0, snapshot.getTimeoutRepeatedFailureCount());
            }
        } catch (Exception e) {
            log.warn("刷新秒杀数据库投递积压指标失败，继续使用上次快照", e);
        }
        try {
            ZSetOperations<String, String> recoveryQueue = redisTemplate.opsForZSet();
            Long pending = recoveryQueue.zCard(SECKILL_DISPATCH_RECOVERY_KEY);
            if (pending != null) {
                redisRecoveryPendingCount = Math.max(0, pending);
            }
        } catch (Exception e) {
            log.warn("刷新秒杀 Redis 恢复队列积压指标失败，继续使用上次快照", e);
        }
    }
}

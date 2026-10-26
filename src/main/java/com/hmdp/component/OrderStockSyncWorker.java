package com.hmdp.component;

import com.hmdp.entity.OrderStockSync;
import com.hmdp.mapper.OrderStockSyncMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_PENDING_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStockSyncWorker {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>();

    static {
        RELEASE_SCRIPT.setLocation(new ClassPathResource("release-order-stock.lua"));
        RELEASE_SCRIPT.setResultType(Long.class);
    }

    private final OrderStockSyncMapper taskMapper;
    private final StringRedisTemplate redisTemplate;

    @Scheduled(fixedDelayString = "${order.stock-sync.delay-ms:5000}")
    public void syncPending() {
        for (OrderStockSync task : taskMapper.findPending()) {
            try {
                Long result = redisTemplate.execute(RELEASE_SCRIPT, List.of(
                        SECKILL_STOCK_KEY + task.getVoucherId(),
                        "seckill:order:" + task.getVoucherId(),
                        "seckill:stock:released:" + task.getOrderId(),
                        SECKILL_ORDER_PENDING_KEY + task.getOrderId()), task.getUserId().toString());
                if (result == null || (result != 0L && result != 1L)) {
                    throw new IllegalStateException("Redis 库存或占用数据缺失，需核对后重试，result=" + result);
                }
                taskMapper.markCompleted(task.getOrderId());
            } catch (Exception e) {
                log.error("关单库存同步失败，将重试，订单ID={}", task.getOrderId(), e);
                try {
                    taskMapper.recordFailure(task.getOrderId());
                } catch (Exception retryError) {
                    // 数据库不可用时任务仍保持未完成，下次扫描会再次处理。
                    log.error("记录库存同步重试失败，订单ID={}", task.getOrderId(), retryError);
                }
            }
        }
    }
}

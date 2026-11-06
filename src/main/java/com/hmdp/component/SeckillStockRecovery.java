package com.hmdp.component;

import com.hmdp.dto.SeckillVoucherLookup;
import com.hmdp.entity.SeckillOrderReservation;
import com.hmdp.mapper.OrderStockSyncMapper;
import com.hmdp.mapper.SeckillOrderDispatchMapper;
import com.hmdp.mapper.SeckillOrderGuardMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.DataType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.SECKILL_DISPATCH_RECOVERY_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/** Rebuilds missing Redis admission state from committed MySQL state, failing closed while work is in flight. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillStockRecovery {

    private final StringRedisTemplate redisTemplate;
    private final SeckillOrderDispatchMapper dispatchMapper;
    private final SeckillOrderGuardMapper orderGuardMapper;
    private final OrderStockSyncMapper stockSyncMapper;

    public boolean recoverMissingStock(SeckillVoucherLookup activity) {
        if (activity == null || activity.getVoucherId() == null || activity.getStock() == null) {
            return false;
        }
        Long voucherId = activity.getVoucherId();
        String stockKey = SECKILL_STOCK_KEY + voucherId;
        if (redisTemplate.opsForValue().get(stockKey) != null) {
            return true;
        }

        // An outbox task may represent a reservation not yet reflected in MySQL stock.
        // A pending close task has already restored MySQL stock but may still increment Redis.
        // In either case, copying the DB value now could lose or double-count inventory.
        if (dispatchMapper.hasPending() || stockSyncMapper.hasPendingForVoucher(voucherId)
                || Boolean.TRUE.equals(redisTemplate.hasKey(SECKILL_DISPATCH_RECOVERY_KEY))) {
            log.error("秒杀库存缺失且存在未完成恢复任务，暂不重建，voucherId={}", voucherId);
            return false;
        }

        String orderKey = "seckill:order:" + voucherId;
        String reservationKey = "seckill:order:reservation:" + voucherId;
        DataType orderType = redisTemplate.type(orderKey);
        DataType reservationType = redisTemplate.type(reservationKey);
        if ((orderType != null && orderType != DataType.NONE && orderType != DataType.SET)
                || (reservationType != null && reservationType != DataType.NONE
                && reservationType != DataType.HASH)) {
            log.error("秒杀占用键类型异常，拒绝重建库存，voucherId={}", voucherId);
            return false;
        }

        List<SeckillOrderReservation> activeReservations = orderGuardMapper.findActiveReservations(voucherId);
        Map<String, String> expected = new LinkedHashMap<>();
        for (SeckillOrderReservation reservation : activeReservations) {
            if (reservation.getUserId() == null || reservation.getOrderId() == null) {
                log.error("数据库秒杀占用记录不完整，拒绝重建库存，voucherId={}", voucherId);
                return false;
            }
            expected.put(reservation.getUserId().toString(), reservation.getOrderId().toString());
        }

        Map<Object, Object> existing = redisTemplate.opsForHash().entries(reservationKey);
        for (Map.Entry<Object, Object> entry : existing.entrySet()) {
            String expectedOrder = expected.get(String.valueOf(entry.getKey()));
            if (!String.valueOf(entry.getValue()).equals(expectedOrder)) {
                log.error("Redis 秒杀占用与 MySQL 活动订单不一致，拒绝重建库存，voucherId={}, userId={}",
                        voucherId, entry.getKey());
                return false;
            }
        }
        Set<String> existingUsers = redisTemplate.opsForSet().members(orderKey);
        if (existingUsers != null && existingUsers.stream().anyMatch(userId -> !expected.containsKey(userId))) {
            log.error("Redis 秒杀用户集合包含 MySQL 中不存在的活动占用，拒绝重建库存，voucherId={}", voucherId);
            return false;
        }

        try {
            if (!expected.isEmpty()) {
                redisTemplate.opsForSet().add(orderKey, expected.keySet().toArray(String[]::new));
                redisTemplate.opsForHash().putAll(reservationKey, expected);
            }
            // Publish stock last: seckill.lua cannot admit requests until every durable reservation is restored.
            redisTemplate.opsForValue().setIfAbsent(stockKey, activity.getStock().toString());
            return redisTemplate.opsForValue().get(stockKey) != null;
        } catch (RuntimeException e) {
            log.error("秒杀数据库库存及用户占用重建失败，voucherId={}", voucherId, e);
            return false;
        }
    }
}

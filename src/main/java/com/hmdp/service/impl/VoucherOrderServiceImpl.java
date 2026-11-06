package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.OrderHistoryItem;
import com.hmdp.dto.SeckillVoucherLookup;
import com.hmdp.component.SeckillActivityCache;
import com.hmdp.component.SeckillStockRecovery;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.SeckillOrderDispatch;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.config.RocketMQConstants;
import com.hmdp.mapper.OrderStockSyncMapper;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.SeckillOrderDispatchMapper;
import com.hmdp.mapper.SeckillOrderGuardMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.SnowflakeIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Map;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_PENDING_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_DISPATCH_PAYLOAD_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_DISPATCH_RECOVERY_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_DUPLICATE_REFUND_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final String SECKILL_ORDER_KEY_PREFIX = "seckill:order:";
    private static final int SECKILL_RESULT_OUT_OF_STOCK = 1;
    private static final int SECKILL_RESULT_DUPLICATE = 2;
    private static final int SECKILL_RESULT_STOCK_NOT_READY = 3;
    private static final int SECKILL_RESULT_NOT_STARTED = 4;
    private static final int SECKILL_RESULT_ENDED = 5;
    private static final int SECKILL_RESULT_VOUCHER_UNAVAILABLE = 7;

    @Resource
    private SnowflakeIdWorker snowflakeIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;
    @Resource
    private SeckillActivityCache seckillActivityCache;

    @Resource
    private SeckillStockRecovery seckillStockRecovery;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private OrderStockSyncMapper orderStockSyncMapper;
    @Resource
    private SeckillOrderDispatchMapper seckillOrderDispatchMapper;
    @Resource
    private SeckillOrderGuardMapper seckillOrderGuardMapper;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> REJECT_DUPLICATE_RESERVATION_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        REJECT_DUPLICATE_RESERVATION_SCRIPT = new DefaultRedisScript<>();
        REJECT_DUPLICATE_RESERVATION_SCRIPT.setLocation(
                new ClassPathResource("reject-duplicate-seckill-reservation.lua"));
        REJECT_DUPLICATE_RESERVATION_SCRIPT.setResultType(Long.class);
    }

    @Override
    public List<OrderHistoryItem> listUserOrders(Long userId, Integer current) {
        Page<VoucherOrder> page = lambdaQuery()
                .eq(VoucherOrder::getUserId, userId)
                .orderByDesc(VoucherOrder::getCreateTime)
                .orderByDesc(VoucherOrder::getId)
                .page(new Page<>(current, 20));
        return page.getRecords().stream().map(order -> {
            Voucher voucher = voucherService.getById(order.getVoucherId());
            return new OrderHistoryItem(
                    String.valueOf(order.getId()),
                    order.getVoucherId(),
                    voucher == null ? null : voucher.getShopId(),
                    voucher == null ? null : voucher.getTitle(),
                    voucher == null ? null : voucher.getPayValue(),
                    voucher == null ? null : voucher.getType(),
                    order.getStatus(),
                    order.getPayType(),
                    order.getCreateTime(),
                    order.getUpdateTime());
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result buyVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Voucher voucher = voucherService.getById(voucherId);
        if (voucher == null || !Integer.valueOf(1).equals(voucher.getStatus())) {
            return Result.fail("优惠券不存在或已下架");
        }
        if (Integer.valueOf(1).equals(voucher.getType())) {
            return Result.fail("该优惠券为秒杀券，请使用秒杀方式购买");
        }

        long orderId = snowflakeIdWorker.nextId();
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setStatus(VoucherOrder.STATUS_UNPAID);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());

        boolean saved = save(order);
        if (!saved) {
            return Result.fail("普通券订单创建失败，请稍后重试");
        }
        return Result.ok(orderId);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = snowflakeIdWorker.nextId();

        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setStatus(VoucherOrder.STATUS_UNPAID);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        String payload;
        try {
            payload = objectMapper.writeValueAsString(order);
        } catch (JsonProcessingException e) {
            log.error("秒杀订单序列化失败，订单ID={}", orderId, e);
            return Result.fail("订单创建失败，请稍后重试");
        }

        Long result;
        try {
            result = executeSeckillScript(voucherId, userId, orderId, payload);
        } catch (RuntimeException e) {
            log.error("秒杀预扣结果待确认，订单ID={}", orderId, e);
            return pendingOrderResult(orderId);
        }
        if (result == null) {
            log.error("秒杀脚本执行结果为空，voucherId={}, userId={}", voucherId, userId);
            return pendingOrderResult(orderId);
        }

        int r = result.intValue();
        if (r == SECKILL_RESULT_STOCK_NOT_READY) {
            if (!tryWarmupSeckillData(voucherId)) {
                log.warn("秒杀库存或活动信息不可用，voucherId={}", voucherId);
                return Result.fail("秒杀活动暂不可用，请稍后重试");
            }
            try {
                result = executeSeckillScript(voucherId, userId, orderId, payload);
            } catch (RuntimeException e) {
                log.error("秒杀预扣结果待确认，订单ID={}", orderId, e);
                return pendingOrderResult(orderId);
            }
            if (result == null) {
                return pendingOrderResult(orderId);
            }
            r = result.intValue();
        }

        if (r != 0) {
            if (r == SECKILL_RESULT_OUT_OF_STOCK) {
                return Result.fail("库存不足");
            }
            if (r == SECKILL_RESULT_DUPLICATE) {
                return Result.fail("不能重复下单");
            }
            if (r == SECKILL_RESULT_NOT_STARTED) {
                return Result.fail("秒杀活动尚未开始");
            }
            if (r == SECKILL_RESULT_ENDED) {
                return Result.fail("秒杀活动已结束");
            }
            if (r == SECKILL_RESULT_VOUCHER_UNAVAILABLE) {
                return Result.fail("秒杀券不存在或已下架");
            }
            if (r == SECKILL_RESULT_STOCK_NOT_READY) {
                log.warn("秒杀库存初始化后仍未生效，voucherId={}", voucherId);
                return Result.fail("秒杀库存未初始化，请稍后重试");
            }
            return Result.fail("系统繁忙，请稍后重试");
        }

        // 先把受理凭据持久化到 MySQL，成功响应不再依赖 Redis 恢复队列单独保存。
        // Redis 记录仍作为数据库暂不可用时的恢复副本，订单创建继续由 MQ 异步完成。
        if (!persistSeckillDispatch(order, payload)) {
            return pendingOrderResult(orderId);
        }
        return Result.ok(orderId);
    }

    private boolean persistSeckillDispatch(VoucherOrder order, String payload) {
        SeckillOrderDispatch task = new SeckillOrderDispatch();
        task.setOrderId(order.getId());
        task.setUserId(order.getUserId());
        task.setPayload(payload);
        task.setTimeoutRetryTime(order.getCreateTime().plusNanos(
                RocketMQConstants.ORDER_PAYMENT_TIMEOUT_MILLIS * 1_000_000L));

        try {
            if (seckillOrderDispatchMapper.insertRecoveredTasks(List.of(task)) > 0) {
                return true;
            }
        } catch (Exception e) {
            log.error("秒杀 Redis 已预占，数据库投递凭据写入结果待确认，订单ID={}", order.getId(), e);
        }

        // INSERT acknowledgement can be lost after commit, or race the Redis recovery worker.
        // Confirm by the generated order ID before deciding whether the caller may report success.
        try {
            return seckillOrderDispatchMapper.findCompletionStatus(order.getId()) != null;
        } catch (Exception e) {
            log.error("秒杀数据库投递凭据无法确认，保留 Redis 恢复副本并返回待确认，订单ID={}", order.getId(), e);
            return false;
        }
    }

    private Result pendingOrderResult(long orderId) {
        return Result.fail("订单状态待确认", Map.of("orderId", Long.toString(orderId), "pending", true));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        VoucherOrder existingOrder = getById(voucherOrder.getId());
        if (existingOrder != null) {
            log.warn("秒杀订单已存在，忽略重复消息，订单ID={}", voucherOrder.getId());
            seckillOrderDispatchMapper.markCompleted(voucherOrder.getId());
            clearSeckillPendingAfterCommit(voucherOrder.getId());
            return;
        }

        seckillOrderGuardMapper.ensureReservationRow(voucherOrder.getUserId(), voucherOrder.getVoucherId());
        if (seckillOrderGuardMapper.reserve(voucherOrder.getUserId(), voucherOrder.getVoucherId(),
                voucherOrder.getId()) != 1) {
            log.warn("用户已有未取消的秒杀订单，丢弃重复订单消息，userId={}, voucherId={}, orderId={}",
                    voucherOrder.getUserId(), voucherOrder.getVoucherId(), voucherOrder.getId());
            int completed = seckillOrderDispatchMapper.markCompleted(voucherOrder.getId());
            boolean trackedDispatch = completed == 1;
            if (!trackedDispatch) {
                Integer currentStatus = seckillOrderDispatchMapper.findCompletionStatus(voucherOrder.getId());
                if (Integer.valueOf(1).equals(currentStatus)) {
                    clearSeckillPendingAfterCommit(voucherOrder.getId());
                    return;
                }
                if (currentStatus != null) {
                    throw new IllegalStateException("完成重复秒杀投递任务失败，订单ID=" + voucherOrder.getId());
                }
                String pendingUserId = stringRedisTemplate.opsForValue()
                        .get(SECKILL_ORDER_PENDING_KEY + voucherOrder.getId());
                if (!voucherOrder.getUserId().toString().equals(pendingUserId)) {
                    log.warn("丢弃无投递记录且无 Redis 预扣凭据的重复消息，订单ID={}", voucherOrder.getId());
                    clearSeckillPendingAfterCommit(voucherOrder.getId());
                    return;
                }
            }

            // A concurrent redelivery of the same payload can lose the guard race while its
            // original transaction is still creating this order. Do not refund that order's
            // one Redis reservation as if it belonged to a different duplicate order ID.
            Long activeOrderId = seckillOrderGuardMapper.findActiveOrderIdForUpdate(
                    voucherOrder.getUserId(), voucherOrder.getVoucherId());
            if (voucherOrder.getId().equals(activeOrderId)) {
                log.info("重复秒杀消息对应同一进行中订单，不回补 Redis 预扣，订单ID={}", voucherOrder.getId());
                clearSeckillPendingAfterCommit(voucherOrder.getId(), trackedDispatch);
                return;
            }
            if (activeOrderId == null) {
                throw new IllegalStateException("重复秒杀消息未找到有效占用订单，订单ID=" + voucherOrder.getId());
            }

            Long refundResult = stringRedisTemplate.execute(REJECT_DUPLICATE_RESERVATION_SCRIPT,
                    List.of(SECKILL_STOCK_KEY + voucherOrder.getVoucherId(),
                            SECKILL_ORDER_KEY_PREFIX + voucherOrder.getVoucherId(),
                            SECKILL_ORDER_DUPLICATE_REFUND_KEY + voucherOrder.getId(),
                            SECKILL_ORDER_PENDING_KEY + voucherOrder.getId(),
                            "seckill:order:reservation:" + voucherOrder.getVoucherId()),
                    voucherOrder.getUserId().toString(), activeOrderId.toString());
            if (refundResult == null || refundResult < 0) {
                throw new IllegalStateException("重复秒杀预扣回补失败，订单ID=" + voucherOrder.getId()
                        + ", result=" + refundResult);
            }
            clearSeckillPendingAfterCommit(voucherOrder.getId(), trackedDispatch);
            return;
        }

        boolean saved = save(voucherOrder);
        if (!saved) {
            throw new IllegalStateException("保存秒杀订单失败，订单ID=" + voucherOrder.getId());
        }

        boolean stockUpdated = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!stockUpdated) {
            throw new IllegalStateException("扣减秒杀库存失败，券ID=" + voucherOrder.getVoucherId());
        }

        seckillOrderDispatchMapper.markCompleted(voucherOrder.getId());
        clearSeckillPendingAfterCommit(voucherOrder.getId());
        log.info("秒杀订单落库成功，订单ID={}", voucherOrder.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closeTimeoutOrder(Long orderId) {
        VoucherOrder order = getById(orderId);
        if (order == null) {
            // 下单与关单分属不同队列，关单消息可能先于落库到达。
            throw new IllegalStateException("订单尚未落库，等待重试，订单ID=" + orderId);
        }

        if (!VoucherOrder.STATUS_UNPAID.equals(order.getStatus())) {
            log.info("订单状态不是未支付，无需关闭，订单ID={}, 状态={}", orderId, order.getStatus());
            return;
        }

        boolean closed = update(new UpdateWrapper<VoucherOrder>()
                .eq("id", orderId)
                .eq("status", VoucherOrder.STATUS_UNPAID)
                .set("status", VoucherOrder.STATUS_CANCELLED)
                .set("update_time", LocalDateTime.now()));
        if (!closed) {
            return;
        }

        if (seckillOrderGuardMapper.release(order.getUserId(), order.getVoucherId(), orderId) != 1) {
            throw new IllegalStateException("释放秒杀订单占用失败，订单ID=" + orderId);
        }

        boolean stockUpdated = seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        if (!stockUpdated) {
            throw new IllegalStateException("回滚秒杀库存失败，订单ID=" + orderId);
        }

        // 关单、数据库库存回补与同步任务在同一本地事务中提交。
        // Redis 由后台任务在提交后幂等更新，失败不会丢失待办。
        if (orderStockSyncMapper.insertTask(orderId, order.getVoucherId(), order.getUserId()) != 1) {
            throw new IllegalStateException("保存库存同步任务失败，订单ID=" + orderId);
        }
        log.info("订单超时已关闭，订单ID={}", orderId);
    }

    private Long executeSeckillScript(Long voucherId, Long userId, Long orderId, String payload) {
        return stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), orderId.toString(), payload
        );
    }

    private boolean tryWarmupSeckillData(Long voucherId) {
        String stockKey = SECKILL_STOCK_KEY + voucherId;
        boolean stockMissing = stringRedisTemplate.opsForValue().get(stockKey) == null;
        SeckillVoucherLookup activity = seckillVoucherMapper.findForSeckill(voucherId);
        if (activity == null || activity.getBeginTime() == null || activity.getEndTime() == null
                || activity.getStock() == null || activity.getVoucherStatus() == null
                || activity.getVoucherType() == null) {
            return false;
        }
        seckillActivityCache.put(activity);
        if (stockMissing && !seckillStockRecovery.recoverMissingStock(activity)) {
            log.error("检测到秒杀库存缺失，但当前数据库状态不足以安全重建，voucherId={}", voucherId);
            return false;
        }
        if (stockMissing) {
            log.info("检测到秒杀库存缺失，已从 MySQL 恢复库存与活动占用，voucherId={}, stock={}",
                    voucherId, activity.getStock());
        }
        return true;
    }

    private void clearSeckillPendingAfterCommit(Long orderId) {
        clearSeckillPendingAfterCommit(orderId, true);
    }

    private void clearSeckillPendingAfterCommit(Long orderId, boolean clearDuplicateRefundMarker) {
        Runnable cleanup = () -> {
            try {
                stringRedisTemplate.delete(SECKILL_ORDER_PENDING_KEY + orderId);
                if (clearDuplicateRefundMarker) {
                    stringRedisTemplate.delete(
                            SECKILL_ORDER_DUPLICATE_REFUND_KEY + orderId);
                }
            } catch (RuntimeException e) {
                log.error("订单已提交，但清理秒杀待处理标记失败，订单ID={}", orderId, e);
            }
            clearDispatchRecoveryRecord(orderId);
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        } else {
            cleanup.run();
        }
    }

    private void clearDispatchRecoveryRecord(Long orderId) {
        try {
            stringRedisTemplate.delete(SECKILL_DISPATCH_PAYLOAD_KEY + orderId);
            stringRedisTemplate.opsForZSet().remove(SECKILL_DISPATCH_RECOVERY_KEY, orderId.toString());
        } catch (Exception e) {
            log.error("清理秒杀 Redis 恢复副本失败，订单ID={}", orderId, e);
        }
    }
}

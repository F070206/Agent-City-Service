package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillOrderDispatchMapper;
import com.hmdp.service.IPaymentService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_PENDING_KEY;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements IPaymentService {

    private final IVoucherOrderService voucherOrderService;
    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillOrderDispatchMapper dispatchMapper;

    @Override
    public Result createPayment(Long orderId, Integer payType) {
        if (!VoucherOrder.PAY_TYPE_BALANCE.equals(payType)
                && !VoucherOrder.PAY_TYPE_ALIPAY.equals(payType)
                && !VoucherOrder.PAY_TYPE_WECHAT.equals(payType)) {
            return Result.fail("支付方式无效");
        }
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }

        if (!VoucherOrder.STATUS_UNPAID.equals(order.getStatus())) {
            return Result.fail("订单状态不正确");
        }

        UpdateWrapper<VoucherOrder> updatePayment = new UpdateWrapper<VoucherOrder>()
                .eq("id", orderId)
                .eq("status", VoucherOrder.STATUS_UNPAID)
                // 与支付回调使用同一截止条件，避免关单补扫延迟时仍启动过期秒杀支付。
                .apply(seckillPaymentDeadlineCondition())
                .set("pay_type", payType)
                .set("update_time", LocalDateTime.now());
        boolean updated = voucherOrderService.update(updatePayment);
        if (!updated) {
            return Result.fail("订单状态已变更，请刷新后重试");
        }

        log.info("创建支付订单：订单ID={}, 支付方式={}", orderId, payType);
        return Result.ok("支付订单创建成功");
    }

    @Override
    public Result simulatePaymentCallback(Long orderId) {
        return handlePaymentCallback(orderId);
    }

    @Override
    public Result wechatPayCallback(Long orderId) {
        log.info("收到微信支付回调：订单ID={}", orderId);
        return handlePaymentCallback(orderId);
    }

    @Override
    public Result alipayCallback(Long orderId) {
        log.info("收到支付宝支付回调：订单ID={}", orderId);
        return handlePaymentCallback(orderId);
    }

    private Result handlePaymentCallback(Long orderId) {
        try {
            VoucherOrder order = voucherOrderService.getById(orderId);
            if (order == null) {
                return Result.fail("订单不存在");
            }

            if (VoucherOrder.STATUS_PAID.equals(order.getStatus())) {
                log.info("订单已支付，订单ID={}", orderId);
                return Result.ok("订单已支付");
            }

            if (!VoucherOrder.STATUS_UNPAID.equals(order.getStatus())) {
                log.warn("订单状态不正确，无法支付，订单ID={}, 状态={}", orderId, order.getStatus());
                return Result.fail("订单状态不正确");
            }

            // 与关单竞争同一数据库行，只有未支付状态能迁移，锁过期也不会覆盖终态。
            LocalDateTime now = LocalDateTime.now();
            UpdateWrapper<VoucherOrder> updatePayment = new UpdateWrapper<VoucherOrder>()
                    .eq("id", orderId)
                    .eq("status", VoucherOrder.STATUS_UNPAID)
                    // 秒杀订单以持久化的 timeout_retry_time 为准；普通券没有 dispatch 记录，不受 15 分钟限制。
                    .apply(seckillPaymentDeadlineCondition())
                    .set("status", VoucherOrder.STATUS_PAID)
                    .set("pay_time", now)
                    .set("update_time", now);
            boolean updated = voucherOrderService.update(updatePayment);
            if (!updated) {
                VoucherOrder current = voucherOrderService.getById(orderId);
                if (current != null && VoucherOrder.STATUS_PAID.equals(current.getStatus())) {
                    return Result.ok("订单已支付");
                }
                return Result.fail("订单状态已变更，无法支付");
            }

            log.info("订单支付成功，订单ID={}", orderId);
            return Result.ok("支付成功");
        } catch (Exception e) {
            log.error("支付回调处理异常，订单ID={}", orderId, e);
            return Result.fail("支付回调处理失败");
        }
    }

    private String seckillPaymentDeadlineCondition() {
        return "(NOT EXISTS (SELECT 1 FROM tb_seckill_order_dispatch d "
                + "WHERE d.order_id = tb_voucher_order.id) "
                + "OR EXISTS (SELECT 1 FROM tb_seckill_order_dispatch d "
                + "WHERE d.order_id = tb_voucher_order.id "
                + "AND COALESCE(d.timeout_retry_time, "
                + "DATE_ADD(tb_voucher_order.create_time, INTERVAL 15 MINUTE)) "
                + "> CURRENT_TIMESTAMP))";
    }

    @Override
    public Result checkPaymentStatus(Long orderId) {
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order != null) {
            UserDTO currentUser = UserHolder.getUser();
            if (currentUser == null || currentUser.getId() == null
                    || !currentUser.getId().equals(order.getUserId())) {
                return Result.fail("订单不属于当前用户");
            }
            return Result.ok(order.getStatus());
        }

        UserDTO user = UserHolder.getUser();
        String pendingUserId = stringRedisTemplate.opsForValue().get(SECKILL_ORDER_PENDING_KEY + orderId);
        if (!StringUtils.hasText(pendingUserId)) {
            Long persistedUserId = dispatchMapper.findPendingUserId(orderId);
            pendingUserId = persistedUserId == null ? null : persistedUserId.toString();
        }
        if (!StringUtils.hasText(pendingUserId)) {
            return Result.fail("订单不存在");
        }
        if (user == null || user.getId() == null || !pendingUserId.equals(String.valueOf(user.getId()))) {
            return Result.fail("订单不属于当前用户");
        }
        return Result.ok(0);
    }
}

package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
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

    @Override
    public Result createPayment(Long orderId, Integer payType) {
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }

        if (!VoucherOrder.STATUS_UNPAID.equals(order.getStatus())) {
            return Result.fail("订单状态不正确");
        }

        boolean updated = voucherOrderService.update(new UpdateWrapper<VoucherOrder>()
                .eq("id", orderId)
                .eq("status", VoucherOrder.STATUS_UNPAID)
                .set("pay_type", payType)
                .set("update_time", LocalDateTime.now()));
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
            boolean updated = voucherOrderService.update(new UpdateWrapper<VoucherOrder>()
                    .eq("id", orderId)
                    .eq("status", VoucherOrder.STATUS_UNPAID)
                    .set("status", VoucherOrder.STATUS_PAID)
                    .set("pay_time", now)
                    .set("update_time", now));
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

    @Override
    public Result checkPaymentStatus(Long orderId) {
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order != null) {
            return Result.ok(order.getStatus());
        }

        UserDTO user = UserHolder.getUser();
        String pendingUserId = stringRedisTemplate.opsForValue().get(SECKILL_ORDER_PENDING_KEY + orderId);
        if (!StringUtils.hasText(pendingUserId)) {
            return Result.fail("订单不存在");
        }
        if (user == null || user.getId() == null || !pendingUserId.equals(String.valueOf(user.getId()))) {
            return Result.fail("订单不属于当前用户");
        }
        return Result.ok(0);
    }
}

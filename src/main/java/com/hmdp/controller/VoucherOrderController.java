package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.SlidingWindowLimit;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/voucher-order")
@SlidingWindowLimit(globalLimit = true)
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @GetMapping("/my")
    public Result myOrders(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        if (current == null || current < 1) {
            return Result.fail("页码必须大于等于 1");
        }
        if (current > 50) {
            return Result.fail("仅支持查询前1000条订单");
        }
        Long userId = UserHolder.getUser().getId();
        return Result.ok(voucherOrderService.listUserOrders(userId, current));
    }

    @PostMapping("/buy/{id}")
    public Result buyVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.buyVoucher(voucherId);
    }

    /**
     * 抢购秒杀券 - 双重限流保护
     * 第一层：全局限流，保护系统不被过载
     * 第二层：用户级限流，防止单用户恶意刷单
     * @param voucherId 优惠券ID
     * @return 订单结果
     */

    @SlidingWindowLimit(
        keySpEL = "#voucherId",
        windowSize = 1,
        timeUnit = TimeUnit.MINUTES,
        maxRequests = 1,
        globalLimit = false
    )
    @PostMapping("/seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }
}

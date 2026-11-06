package com.hmdp.controller;


import com.hmdp.config.AdminApiGuard;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.dto.SeckillActivityUpdateRequest;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.SlidingWindowLimit;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/voucher")
@SlidingWindowLimit(globalLimit = true)
public class VoucherController {

    @Resource
    private IVoucherService voucherService;
    @Resource
    private AdminApiGuard adminApiGuard;

    /**
     * 新增秒杀券
     * @param voucher 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("/seckill")
    public Result addSeckillVoucher(@RequestHeader("X-Admin-Token") String token,
            @RequestBody Voucher voucher) {
        adminApiGuard.verify(token);
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }

    @PutMapping("/seckill/{voucherId}/activity")
    public Result updateSeckillActivity(@RequestHeader("X-Admin-Token") String token,
            @PathVariable Long voucherId, @RequestBody SeckillActivityUpdateRequest request) {
        adminApiGuard.verify(token);
        voucherService.updateSeckillActivity(voucherId, request);
        return Result.ok();
    }

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addVoucher(@RequestHeader("X-Admin-Token") String token, @RequestBody Voucher voucher) {
        adminApiGuard.verify(token);
        if (!voucherService.save(voucher)) {
            return Result.fail("新增优惠券失败，请稍后重试");
        }
        return Result.ok(voucher.getId());
    }


    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }
}

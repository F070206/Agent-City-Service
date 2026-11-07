package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillVoucherLookup;
import com.hmdp.dto.SeckillActivityUpdateRequest;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.component.SeckillActivityCache;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Objects;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private SeckillActivityCache seckillActivityCache;
    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        if (!save(voucher) || voucher.getId() == null) {
            throw new IllegalStateException("保存秒杀优惠券失败");
        }
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        if (!seckillVoucherService.save(seckillVoucher)) {
            throw new IllegalStateException("保存秒杀活动信息失败");
        }
        SeckillVoucherLookup activity = new SeckillVoucherLookup();
        activity.setVoucherId(voucher.getId());
        activity.setStock(voucher.getStock());
        activity.setBeginTime(voucher.getBeginTime());
        activity.setEndTime(voucher.getEndTime());
        activity.setVoucherStatus(voucher.getStatus());
        activity.setVoucherType(voucher.getType());
        Runnable publish = () -> {
            // Redis must not advertise an activity whose database transaction rolled back.
            stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
            seckillActivityCache.put(activity);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    @Override
    @Transactional
    public void updateSeckillActivity(Long voucherId, SeckillActivityUpdateRequest request) {
        if (voucherId == null || request == null || request.getStatus() == null
                || request.getBeginTime() == null || request.getEndTime() == null
                || !request.getBeginTime().isBefore(request.getEndTime())
                || (request.getStatus() != 0 && request.getStatus() != 1)) {
            throw new IllegalArgumentException("活动状态或起止时间无效");
        }
        SeckillVoucherLookup current = seckillVoucherMapper.findForSeckill(voucherId);
        if (current == null || !Objects.equals(current.getVoucherType(), 1)) {
            throw new IllegalArgumentException("秒杀券不存在");
        }
        Voucher voucherPatch = new Voucher();
        voucherPatch.setId(voucherId);
        voucherPatch.setStatus(request.getStatus());
        boolean voucherUpdated = updateById(voucherPatch);
        int activityUpdated = seckillVoucherMapper.updateActivity(voucherId,
                request.getBeginTime(), request.getEndTime());
        if (!voucherUpdated || activityUpdated != 1) {
            throw new IllegalStateException("秒杀活动更新失败");
        }

        SeckillVoucherLookup updated = seckillVoucherMapper.findForSeckill(voucherId);
        if (updated == null) {
            throw new IllegalStateException("秒杀活动更新后无法读取最新版本");
        }
        Runnable refresh = () -> seckillActivityCache.refreshAfterCommit(updated);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refresh.run();
                }
            });
        } else {
            refresh.run();
        }
    }
}

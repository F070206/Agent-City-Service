package com.hmdp.component;

import com.hmdp.dto.SeckillVoucherLookup;
import com.hmdp.mapper.SeckillVoucherMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 启动时将数据库中的秒杀库存同步到 Redis，避免旧数据没有经过新增接口时缺少库存 Key。
 */
@Component
@Slf4j
public class SeckillStockInitializer implements SmartInitializingSingleton {

    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private SeckillActivityCache seckillActivityCache;
    @Resource
    private SeckillStockRecovery seckillStockRecovery;

    @Override
    public void afterSingletonsInstantiated() {
        try {
            List<SeckillVoucherLookup> vouchers = seckillVoucherMapper.findAllForSeckill();
            if (vouchers == null || vouchers.isEmpty()) {
                log.info("未查询到秒杀券数据，跳过秒杀库存预热");
                return;
            }
            int initialized = 0;
            int needsReconciliation = 0;
            for (SeckillVoucherLookup voucher : vouchers) {
                if (voucher.getVoucherId() == null) {
                    continue;
                }
                Long voucherId = voucher.getVoucherId();
                String stockKey = SECKILL_STOCK_KEY + voucherId;
                if (voucher.getStock() != null && stringRedisTemplate.opsForValue().get(stockKey) == null) {
                    if (seckillStockRecovery.recoverMissingStock(voucher)) {
                        initialized++;
                    } else {
                        needsReconciliation++;
                    }
                }
                seckillActivityCache.put(voucher);
            }
            log.info("秒杀库存预热完成，新增 {} 条，需人工对账 {} 条", initialized, needsReconciliation);
        } catch (Exception e) {
            log.error("秒杀库存预热失败", e);
            throw new IllegalStateException("秒杀库存预热失败", e);
        }
    }
}

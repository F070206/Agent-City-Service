package com.hmdp.component;

import com.hmdp.mapper.VoucherMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

@Component
@Profile("!micro")
@Slf4j
public class BloomFilterInitializer implements SmartInitializingSingleton {

    @Resource
    private RBloomFilter<Long> voucherBloomFilter;

    @Resource
    private VoucherMapper voucherMapper;

    @Override
    public void afterSingletonsInstantiated() {
        log.info("========== 开始预热布隆过滤器 ==========");
        this.initVoucherBloomFilter();
    }

    private void initVoucherBloomFilter() {
        try {
            long startTime = System.currentTimeMillis();

            // 保留共享过滤器中的已有 ID，避免滚动启动时出现短暂的假阴性。
            voucherBloomFilter.tryInit(10000L, 0.01);

            // 修正：使用 voucherMapper 且同样采用分页（更安全）
            int pageSize = 1000;
            long lastId = 0;
            List<Long> voucherIds;
            do {
                voucherIds = voucherMapper.selectIdsAfter(lastId, pageSize);
                for (Long id : voucherIds) {
                    voucherBloomFilter.add(id);
                }
                if (!voucherIds.isEmpty()) {
                    lastId = voucherIds.get(voucherIds.size() - 1);
                }
            } while (voucherIds.size() == pageSize);

            log.info("优惠券布隆过滤器预热完成，耗时 {} ms", System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("优惠券布隆过滤器预热失败", e);
            throw new IllegalStateException("优惠券布隆过滤器预热失败", e);
        }
    }
}

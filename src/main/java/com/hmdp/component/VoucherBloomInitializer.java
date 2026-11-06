package com.hmdp.component;

import com.hmdp.mapper.VoucherMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("micro-order")
public class VoucherBloomInitializer implements SmartInitializingSingleton {
    private final VoucherMapper mapper;
    private final RBloomFilter<Long> voucherBloomFilter;

    public VoucherBloomInitializer(VoucherMapper mapper,
            @Qualifier("voucherBloomFilter") RBloomFilter<Long> voucherBloomFilter) {
        this.mapper = mapper;
        this.voucherBloomFilter = voucherBloomFilter;
    }

    @Override
    public void afterSingletonsInstantiated() {
        voucherBloomFilter.tryInit(10000L, 0.01);
        long lastId = 0;
        List<Long> ids;
        do {
            ids = mapper.selectIdsAfter(lastId, 1000);
            ids.forEach(voucherBloomFilter::add);
            if (!ids.isEmpty()) {
                lastId = ids.get(ids.size() - 1);
            }
        } while (ids.size() == 1000);
    }
}

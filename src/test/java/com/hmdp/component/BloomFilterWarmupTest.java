package com.hmdp.component;

import com.hmdp.mapper.VoucherMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class BloomFilterWarmupTest {
    @Test
    @SuppressWarnings("unchecked")
    void voucherWarmupKeepsExistingSharedFilter() {
        VoucherMapper mapper = mock(VoucherMapper.class);
        RBloomFilter<Long> filter = mock(RBloomFilter.class);
        when(mapper.selectIdsAfter(0, 1000)).thenReturn(List.of(3L));
        new VoucherBloomInitializer(mapper, filter).afterSingletonsInstantiated();
        verify(filter, never()).delete();
        verify(filter).tryInit(10000L, 0.01);
        verify(filter).add(3L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void monolithWarmupFailsStartupWhenVoucherIdsCannotBeLoaded() {
        BloomFilterInitializer initializer = new BloomFilterInitializer();
        VoucherMapper mapper = mock(VoucherMapper.class);
        ReflectionTestUtils.setField(initializer, "voucherMapper", mapper);
        ReflectionTestUtils.setField(initializer, "voucherBloomFilter", mock(RBloomFilter.class));
        when(mapper.selectIdsAfter(0, 1000)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, initializer::afterSingletonsInstantiated);
    }
}

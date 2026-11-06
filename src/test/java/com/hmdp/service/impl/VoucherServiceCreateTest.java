package com.hmdp.service.impl;

import com.hmdp.component.SeckillActivityCache;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VoucherServiceCreateTest {
    @Test
    void failedBaseVoucherSaveStopsBeforeCreatingSeckillDetails() {
        VoucherMapper mapper = mock(VoucherMapper.class);
        ISeckillVoucherService seckillService = mock(ISeckillVoucherService.class);
        VoucherServiceImpl service = service(mapper, seckillService);

        assertThrows(IllegalStateException.class,
                () -> service.addSeckillVoucher(new Voucher().setStock(5)));

        verify(mapper).insert(any(Voucher.class));
        verifyNoInteractions(seckillService);
    }

    @Test
    void failedSeckillDetailsSaveThrowsSoTransactionCanRollbackBaseVoucher() {
        VoucherMapper mapper = mock(VoucherMapper.class);
        when(mapper.insert(any(Voucher.class))).thenAnswer(invocation -> {
            ((Voucher) invocation.getArgument(0)).setId(12L);
            return 1;
        });
        ISeckillVoucherService seckillService = mock(ISeckillVoucherService.class);
        when(seckillService.save(any(SeckillVoucher.class))).thenReturn(false);
        VoucherServiceImpl service = service(mapper, seckillService);

        assertThrows(IllegalStateException.class,
                () -> service.addSeckillVoucher(new Voucher().setStock(5)));

        verify(seckillService).save(any(SeckillVoucher.class));
        verifyNoInteractions((StringRedisTemplate) ReflectionTestUtils.getField(service, "stringRedisTemplate"));
    }

    private VoucherServiceImpl service(VoucherMapper mapper, ISeckillVoucherService seckillService) {
        VoucherServiceImpl service = new VoucherServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", mock(StringRedisTemplate.class));
        ReflectionTestUtils.setField(service, "seckillActivityCache", mock(SeckillActivityCache.class));
        ReflectionTestUtils.setField(service, "seckillVoucherMapper", mock(SeckillVoucherMapper.class));
        return service;
    }
}

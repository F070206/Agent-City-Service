package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.SeckillOrderGuardMapper;
import com.hmdp.mapper.SeckillOrderDispatchMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SeckillOrderRetryTest {

    @Test
    void transientDatabaseFailureKeepsRedisReservationForMessageRetry() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        VoucherOrderMapper mapper = mock(VoucherOrderMapper.class);
        SeckillOrderGuardMapper guard = mock(SeckillOrderGuardMapper.class);
        SeckillOrderDispatchMapper dispatch = mock(SeckillOrderDispatchMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "seckillOrderGuardMapper", guard);
        ReflectionTestUtils.setField(service, "seckillOrderDispatchMapper", dispatch);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        when(guard.reserve(7L, 10L, 1L)).thenReturn(1);
        when(mapper.insert(any(VoucherOrder.class))).thenReturn(0);

        VoucherOrder order = new VoucherOrder();
        order.setId(1L);
        order.setVoucherId(10L);
        order.setUserId(7L);
        assertThrows(IllegalStateException.class, () -> service.createVoucherOrder(order));
        verifyNoInteractions(redis);
    }
}

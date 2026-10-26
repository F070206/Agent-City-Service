package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentServiceImplTest {

    private IVoucherOrderService orders;
    private PaymentServiceImpl payments;
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        orders = mock(IVoucherOrderService.class);
        redis = mock(StringRedisTemplate.class);
        payments = new PaymentServiceImpl(orders, redis);
        when(orders.getById(1L)).thenReturn(new VoucherOrder().setId(1L)
                .setStatus(VoucherOrder.STATUS_UNPAID));
    }

    @Test
    void creatingPaymentMustNotSucceedWhenDatabaseUpdateFails() {
        assertFalse(payments.createPayment(1L, VoucherOrder.PAY_TYPE_WECHAT).getSuccess());
    }

    @Test
    void callbackMustNotSucceedWhenDatabaseUpdateFails() {
        assertFalse(payments.simulatePaymentCallback(1L).getSuccess());
    }

    @Test
    void callbackLosingToTimeoutMustNotReportSuccess() {
        when(orders.getById(1L)).thenReturn(new VoucherOrder().setStatus(VoucherOrder.STATUS_UNPAID),
                new VoucherOrder().setStatus(VoucherOrder.STATUS_CANCELLED));
        assertFalse(payments.simulatePaymentCallback(1L).getSuccess());
        verify(orders, never()).updateById(any());
    }

    @Test
    void concurrentSuccessfulCallbackIsIdempotent() {
        when(orders.getById(1L)).thenReturn(new VoucherOrder().setStatus(VoucherOrder.STATUS_UNPAID),
                new VoucherOrder().setStatus(VoucherOrder.STATUS_PAID));
        assertTrue(payments.wechatPayCallback(1L).getSuccess());
    }

    @Test
    void duplicateCallbackDoesNotWriteAgain() {
        when(orders.getById(1L)).thenReturn(new VoucherOrder().setStatus(VoucherOrder.STATUS_PAID));
        assertTrue(payments.alipayCallback(1L).getSuccess());
        verify(orders, never()).update(any(Wrapper.class));
        verifyNoInteractions(redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void paymentOnlyUpdatesUnpaidOrderWithoutDependingOnRedis() {
        when(orders.update(any(Wrapper.class))).thenAnswer(invocation -> {
            UpdateWrapper<VoucherOrder> update = invocation.getArgument(0);
            assertTrue(update.getSqlSegment().contains("id ="));
            assertTrue(update.getSqlSegment().contains("status ="));
            assertTrue(update.getSqlSet().contains("pay_time="));
            assertTrue(update.getParamNameValuePairs().containsValue(VoucherOrder.STATUS_UNPAID));
            return true;
        });
        assertTrue(payments.simulatePaymentCallback(1L).getSuccess());
        verifyNoInteractions(redis);
    }

    @Test
    void creatingPaymentDoesNotWriteStaleStatusBack() {
        when(orders.update(any(Wrapper.class))).thenAnswer(invocation -> {
            UpdateWrapper<?> update = invocation.getArgument(0);
            assertFalse(update.getSqlSet().contains("status="));
            assertTrue(update.getSqlSegment().contains("status ="));
            return true;
        });
        assertTrue(payments.createPayment(1L, VoucherOrder.PAY_TYPE_WECHAT).getSuccess());
        verify(orders, never()).updateById(any());
    }

    @Test
    void databaseExceptionIsReturnedAsFailure() {
        when(orders.update(any(Wrapper.class))).thenThrow(new IllegalStateException("database unavailable"));
        assertFalse(payments.simulatePaymentCallback(1L).getSuccess());
    }
}

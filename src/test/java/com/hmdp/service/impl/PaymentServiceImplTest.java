package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.dto.UserDTO;
import com.hmdp.mapper.SeckillOrderDispatchMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
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
    private SeckillOrderDispatchMapper dispatch;

    @BeforeEach
    void setUp() {
        orders = mock(IVoucherOrderService.class);
        redis = mock(StringRedisTemplate.class);
        dispatch = mock(SeckillOrderDispatchMapper.class);
        payments = new PaymentServiceImpl(orders, redis, dispatch);
        when(orders.getById(1L)).thenReturn(new VoucherOrder().setId(1L)
                .setStatus(VoucherOrder.STATUS_UNPAID));
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void pendingOrderRemainsQueryableAfterRedisPendingKeyExpires() {
        when(orders.getById(2L)).thenReturn(null);
        when(redis.opsForValue()).thenReturn(mock(org.springframework.data.redis.core.ValueOperations.class));
        when(dispatch.findPendingUserId(2L)).thenReturn(7L);
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        assertEquals(0, payments.checkPaymentStatus(2L).getData());
    }

    @Test
    void persistedOrderStatusRequiresOwner() {
        when(orders.getById(1L)).thenReturn(new VoucherOrder().setId(1L).setUserId(7L)
                .setStatus(VoucherOrder.STATUS_PAID));
        UserDTO user = new UserDTO();
        user.setId(8L);
        UserHolder.saveUser(user);
        assertFalse(payments.checkPaymentStatus(1L).getSuccess());
        user.setId(7L);
        assertEquals(VoucherOrder.STATUS_PAID, payments.checkPaymentStatus(1L).getData());
    }

    @Test
    void creatingPaymentMustNotSucceedWhenDatabaseUpdateFails() {
        assertFalse(payments.createPayment(1L, VoucherOrder.PAY_TYPE_WECHAT).getSuccess());
    }

    @Test
    void creatingPaymentRejectsUnsupportedPayTypesBeforeReadingOrder() {
        for (Integer payType : new Integer[]{null, 0, 4, -1}) {
            assertFalse(payments.createPayment(1L, payType).getSuccess());
        }
        verifyNoInteractions(orders);
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
            assertTrue(update.getSqlSegment().contains("NOT EXISTS"));
            assertTrue(update.getSqlSegment().contains("COALESCE(d.timeout_retry_time"));
            assertTrue(update.getSqlSegment().contains("DATE_ADD(tb_voucher_order.create_time, INTERVAL 15 MINUTE)"));
            assertTrue(update.getSqlSegment().contains("> CURRENT_TIMESTAMP"));
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
            assertTrue(update.getSqlSegment().contains("NOT EXISTS"));
            assertTrue(update.getSqlSegment().contains("COALESCE(d.timeout_retry_time"));
            assertTrue(update.getSqlSegment().contains("> CURRENT_TIMESTAMP"));
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

package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IPaymentService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class PaymentControllerTest {

    @Test
    void failedPaymentCreationMustNotReturnPaymentUrl() {
        IPaymentService payments = mock(IPaymentService.class);
        IVoucherOrderService orders = mock(IVoucherOrderService.class);
        PaymentController controller = new PaymentController(payments, orders);
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        try {
            when(orders.getById(1L)).thenReturn(new VoucherOrder().setId(1L).setUserId(7L)
                    .setStatus(VoucherOrder.STATUS_UNPAID));
            Result failure = Result.fail("订单状态已变更");
            when(payments.createPayment(1L, VoucherOrder.PAY_TYPE_WECHAT)).thenReturn(failure);
            assertSame(failure, controller.wechatPay(1L));
        } finally {
            UserHolder.removeUser();
        }
    }
}

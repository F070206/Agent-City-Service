package com.hmdp.controller;

import com.hmdp.dto.OrderHistoryItem;
import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import com.hmdp.dto.UserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class VoucherOrderHistoryControllerTest {
    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    void rejectsInvalidHistoryPageWithoutQueryingService() {
        IVoucherOrderService service = mock(IVoucherOrderService.class);
        VoucherOrderController controller = new VoucherOrderController();
        ReflectionTestUtils.setField(controller, "voucherOrderService", service);

        Result invalid = controller.myOrders(0);
        Result beyondLimit = controller.myOrders(51);

        assertFalse(invalid.getSuccess());
        assertTrue(invalid.getErrorMsg().contains("页码"));
        assertFalse(beyondLimit.getSuccess());
        assertTrue(beyondLimit.getErrorMsg().contains("前1000条"));
        verifyNoInteractions(service);
    }

    @Test
    void returnsOnlyOrdersQueriedForAuthenticatedUser() {
        IVoucherOrderService service = mock(IVoucherOrderService.class);
        VoucherOrderController controller = new VoucherOrderController();
        ReflectionTestUtils.setField(controller, "voucherOrderService", service);
        UserDTO user = new UserDTO();
        user.setId(77L);
        UserHolder.saveUser(user);
        List<OrderHistoryItem> expected = List.of(new OrderHistoryItem(
                "9223372036854770000", 5L, 9L, "测试代金券", 1000L, 0, 1, null, null, null));
        when(service.listUserOrders(77L, 2)).thenReturn(expected);

        Result result = controller.myOrders(2);

        assertSame(expected, result.getData());
        verify(service).listUserOrders(77L, 2);
    }
}

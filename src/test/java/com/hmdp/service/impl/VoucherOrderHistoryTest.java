package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VoucherOrderHistoryTest {
    @Test
    void pagesOnlyTheRequestedUsersOrdersAndAddsVoucherDetails() {
        VoucherOrderMapper mapper = mock(VoucherOrderMapper.class);
        VoucherOrder order = new VoucherOrder().setId(123L).setUserId(77L).setVoucherId(5L)
                .setStatus(VoucherOrder.STATUS_UNPAID).setCreateTime(LocalDateTime.now());
        doAnswer(invocation -> {
            Page<VoucherOrder> page = invocation.getArgument(0);
            page.setRecords(List.of(order));
            return page;
        }).when(mapper).selectPage(any(Page.class), any());
        IVoucherService vouchers = mock(IVoucherService.class);
        when(vouchers.getById(5L)).thenReturn(new Voucher().setId(5L).setShopId(9L)
                .setTitle("测试代金券").setPayValue(1000L).setType(0));
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "voucherService", vouchers);

        var result = service.listUserOrders(77L, 2);

        assertEquals(1, result.size());
        assertEquals("123", result.get(0).orderId());
        assertEquals(9L, result.get(0).shopId());
        assertEquals("测试代金券", result.get(0).voucherTitle());
        org.mockito.ArgumentCaptor<Page<VoucherOrder>> pageCaptor =
                org.mockito.ArgumentCaptor.forClass(Page.class);
        verify(mapper).selectPage(pageCaptor.capture(), any());
        assertEquals(2, pageCaptor.getValue().getCurrent());
        assertEquals(20, pageCaptor.getValue().getSize());
        verify(vouchers).getById(5L);
    }
}

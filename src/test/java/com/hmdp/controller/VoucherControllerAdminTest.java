package com.hmdp.controller;

import com.hmdp.config.AdminApiGuard;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class VoucherControllerAdminTest {
    private final IVoucherService voucherService = mock(IVoucherService.class);
    private MockMvc mockMvc;

    @Test
    void emptyAdminTokenDisablesWriteAccess() {
        AdminApiGuard guard = new AdminApiGuard("");
        assertThrows(SecurityException.class, () -> guard.verify("any-token"));
    }

    @BeforeEach
    void setUp() {
        VoucherController controller = new VoucherController();
        ReflectionTestUtils.setField(controller, "voucherService", voucherService);
        ReflectionTestUtils.setField(controller, "adminApiGuard", new AdminApiGuard("secret"));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WebExceptionAdvice())
                .build();
    }

    @Test
    void couponWriteEndpointsRejectMissingOrInvalidAdminToken() throws Exception {
        for (String path : new String[]{"/voucher", "/voucher/seckill"}) {
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(post(path).header("X-Admin-Token", "wrong")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/voucher/seckill/1/activity").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/voucher/seckill/1/activity").header("X-Admin-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(voucherService);
    }

    @Test
    void configuredAdminTokenAllowsBothCouponWriteEndpoints() throws Exception {
        when(voucherService.save(any(Voucher.class))).thenReturn(true);

        mockMvc.perform(post("/voucher").header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/voucher/seckill").header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        verify(voucherService).save(any(Voucher.class));
        verify(voucherService).addSeckillVoucher(any(Voucher.class));
    }

    @Test
    void failedOrdinaryCouponSaveReturnsFailure() throws Exception {
        when(voucherService.save(any(Voucher.class))).thenReturn(false);

        mockMvc.perform(post("/voucher").header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void publicCouponListRemainsAvailableWithoutAdminToken() throws Exception {
        when(voucherService.queryVoucherOfShop(1L)).thenReturn(Result.ok(java.util.List.of()));

        mockMvc.perform(get("/voucher/list/1"))
                .andExpect(status().isOk());

        verify(voucherService).queryVoucherOfShop(1L);
    }

    @Test
    void validAdminTokenCanUpdateSeckillActivity() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/voucher/seckill/1/activity").header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":1,\"beginTime\":\"2026-11-04T10:00:00\",\"endTime\":\"2026-11-05T10:00:00\"}"))
                .andExpect(status().isOk());

        verify(voucherService).updateSeckillActivity(org.mockito.ArgumentMatchers.eq(1L), any());
    }
}

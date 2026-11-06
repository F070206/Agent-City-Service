package com.hmdp.controller;

import com.hmdp.config.AdminApiGuard;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShopControllerAdminTest {
    private final IShopService shopService = mock(IShopService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ShopController controller = new ShopController();
        ReflectionTestUtils.setField(controller, "shopService", shopService);
        ReflectionTestUtils.setField(controller, "adminApiGuard", new AdminApiGuard("secret"));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WebExceptionAdvice())
                .build();
    }

    @Test
    void shopWriteEndpointsRejectMissingOrInvalidAdminToken() throws Exception {
        for (var request : new org.springframework.test.web.servlet.RequestBuilder[]{
                post("/shop").contentType(MediaType.APPLICATION_JSON).content("{}"),
                put("/shop").contentType(MediaType.APPLICATION_JSON).content("{}")}) {
            mockMvc.perform(request).andExpect(status().isBadRequest());
        }
        for (var request : new org.springframework.test.web.servlet.RequestBuilder[]{
                post("/shop").header("X-Admin-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"),
                put("/shop").header("X-Admin-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")}) {
            mockMvc.perform(request).andExpect(status().isForbidden());
        }
        verifyNoInteractions(shopService);
    }

    @Test
    void configuredAdminTokenAllowsShopWritesWhilePublicReadsRemainAvailable() throws Exception {
        when(shopService.saveShop(any(Shop.class))).thenReturn(Result.ok(1L));
        when(shopService.update(any(Shop.class))).thenReturn(Result.ok());
        when(shopService.queryById(1L)).thenReturn(Result.ok(new Shop().setId(1L)));

        mockMvc.perform(post("/shop").header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/shop").header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/shop/1")).andExpect(status().isOk());

        verify(shopService).saveShop(any(Shop.class));
        verify(shopService).update(any(Shop.class));
        verify(shopService).queryById(1L);
    }
}

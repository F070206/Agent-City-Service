package com.hmdp.controller;

import com.hmdp.client.ContentCatalog;
import com.hmdp.client.InternalApiGuard;
import com.hmdp.config.InternalApiInterceptor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class InternalContentControllerPagingTest {
    @Test
    void rejectsInvalidTokenBeforeBindingShopIdList() throws Exception {
        ContentCatalog catalog = mock(ContentCatalog.class);
        InternalApiGuard guard = new InternalApiGuard("secret");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalContentController(catalog, guard))
                .addInterceptors(new InternalApiInterceptor(guard))
                .build();

        mvc.perform(get("/internal/content/shops/ids").header("X-Internal-Token", "wrong")
                        .queryParam("ids", "not-a-number"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(catalog);
    }

    @Test
    void fullTableEndpointsAreNotExposed() throws Exception {
        ContentCatalog catalog = mock(ContentCatalog.class);
        when(catalog.shopsAfter(0, 200)).thenReturn(List.of());
        when(catalog.blogsAfter(0, 200)).thenReturn(List.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalContentController(catalog, new InternalApiGuard("secret")))
                .build();

        mvc.perform(get("/internal/content/shops").header("X-Internal-Token", "secret"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/content/blogs").header("X-Internal-Token", "secret"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/internal/content/shops/page").header("X-Internal-Token", "secret")
                        .queryParam("afterId", "0").queryParam("limit", "200"))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/content/blogs/page").header("X-Internal-Token", "secret")
                        .queryParam("afterId", "0").queryParam("limit", "200"))
                .andExpect(status().isOk());

        verify(catalog).shopsAfter(0, 200);
        verify(catalog).blogsAfter(0, 200);
    }
}

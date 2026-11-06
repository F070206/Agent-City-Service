package com.hmdp.ai.controller;

import com.hmdp.ai.service.AiAgentService;
import com.hmdp.config.AdminApiGuard;
import com.hmdp.config.WebExceptionAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiAgentControllerAdminTest {
    private final AiAgentService service = mock(AiAgentService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AiAgentController controller = new AiAgentController(service, new AdminApiGuard("secret"));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WebExceptionAdvice()).build();
    }

    @Test
    void indexRebuildEndpointsRejectMissingOrInvalidAdminToken() throws Exception {
        mvc.perform(post("/ai/agent/index/rebuild/all")).andExpect(status().isBadRequest());
        mvc.perform(post("/ai/agent/index/rebuild/shop/8")).andExpect(status().isBadRequest());
        mvc.perform(post("/ai/agent/index/rebuild/all").header("X-Admin-Token", "wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/ai/agent/index/rebuild/shop/8").header("X-Admin-Token", "wrong"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void validAdminTokenAllowsBothIndexRebuildOperations() throws Exception {
        mvc.perform(post("/ai/agent/index/rebuild/all").header("X-Admin-Token", "secret"))
                .andExpect(status().isOk());
        mvc.perform(post("/ai/agent/index/rebuild/shop/8").header("X-Admin-Token", "secret"))
                .andExpect(status().isOk());
        verify(service).rebuildAll();
        verify(service).rebuildShop(8L);
    }
}

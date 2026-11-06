package com.hmdp.controller;

import com.hmdp.client.InternalApiGuard;
import com.hmdp.client.LocalUserDirectory;
import com.hmdp.config.InternalApiExceptionAdvice;
import com.hmdp.config.InternalApiInterceptor;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalUserControllerLimitTest {
    @Test
    void rejectsInvalidTokenBeforeBindingInternalListParameters() throws Exception {
        LocalUserDirectory users = mock(LocalUserDirectory.class);
        InternalApiGuard guard = new InternalApiGuard("secret");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalUserController(users, guard))
                .setControllerAdvice(new InternalApiExceptionAdvice())
                .addInterceptors(new InternalApiInterceptor(guard))
                .build();

        mvc.perform(get("/internal/users").header("X-Internal-Token", "wrong")
                        .queryParam("ids", "not-a-number"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(users);
    }

    @Test
    void rejectsUserBatchLargerThanFiveHundredBeforeDatabaseLookup() throws Exception {
        LocalUserDirectory users = mock(LocalUserDirectory.class);
        when(users.findByIds(anyList())).thenReturn(List.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalUserController(users, new InternalApiGuard("secret")))
                .setControllerAdvice(new InternalApiExceptionAdvice())
                .build();
        String ids = LongStream.rangeClosed(1, 501).mapToObj(Long::toString).collect(Collectors.joining(","));

        mvc.perform(get("/internal/users").header("X-Internal-Token", "secret").queryParam("ids", ids))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(users);
    }

    @Test
    void allowsUserBatchOfFiveHundred() throws Exception {
        LocalUserDirectory users = mock(LocalUserDirectory.class);
        when(users.findByIds(anyList())).thenReturn(List.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalUserController(users, new InternalApiGuard("secret")))
                .setControllerAdvice(new InternalApiExceptionAdvice())
                .build();
        String ids = LongStream.rangeClosed(1, 500).mapToObj(Long::toString).collect(Collectors.joining(","));

        mvc.perform(get("/internal/users").header("X-Internal-Token", "secret").queryParam("ids", ids))
                .andExpect(status().isOk());

        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(users).findByIds(idsCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(500, idsCaptor.getValue().size());
    }
}

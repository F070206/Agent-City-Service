package com.hmdp.controller;

import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserLogoutControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void logoutRevokesTokenForSubsequentProtectedRequests() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        AtomicBoolean tokenActive = new AtomicBoolean(true);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("login:token:test-token"))
                .thenAnswer(invocation -> tokenActive.get() ? Map.of("id", "7") : Map.of());
        when(redis.expire("login:token:test-token", 36000L, java.util.concurrent.TimeUnit.MINUTES))
                .thenAnswer(invocation -> tokenActive.get());
        doAnswer(invocation -> tokenActive.getAndSet(false))
                .when(redis).delete("login:token:test-token");

        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new RefreshTokenInterceptor(redis), new LoginInterceptor())
                .build();

        mvc.perform(post("/user/logout"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/user/me").header("authorization", "test-token"))
                .andExpect(status().isOk());
        mvc.perform(post("/user/logout").header("authorization", "test-token"))
                .andExpect(status().isOk());
        mvc.perform(get("/user/me").header("authorization", "test-token"))
                .andExpect(status().isUnauthorized());
        verify(redis).delete("login:token:test-token");
    }
}

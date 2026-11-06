package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefreshTokenInterceptorTest {
    @Test
    @SuppressWarnings("unchecked")
    void validSessionStillAuthenticatesAndRefreshesExpiry() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("login:token:test-token")).thenReturn(java.util.Map.of("id", "7"));
        when(redis.expire(eq("login:token:test-token"), anyLong(), any())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("authorization", "test-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(new RefreshTokenInterceptor(redis).preHandle(request, response, new Object()));
        assertTrue(new LoginInterceptor().preHandle(request, response, new Object()));
        assertEquals(7L, UserHolder.getUser().getId());
        verify(redis).expire(eq("login:token:test-token"), anyLong(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void incompleteSessionWithoutUserIdMustNotAuthenticate() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("login:token:test-token")).thenReturn(java.util.Map.of("nickName", "visitor"));
        when(redis.expire(eq("login:token:test-token"), anyLong(), any())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("authorization", "test-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(new RefreshTokenInterceptor(redis).preHandle(request, response, new Object()));
        assertFalse(new LoginInterceptor().preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
        verify(redis, never()).expire(anyString(), anyLong(), any());
    }

    @AfterEach
    void clear() {
        UserHolder.removeUser();
    }

    @Test
    void requestWithoutTokenMustNotInheritPreviousThreadIdentity() throws Exception {
        UserDTO previous = new UserDTO();
        previous.setId(7L);
        UserHolder.saveUser(previous);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = new RefreshTokenInterceptor(redis).preHandle(request, response, new Object());

        assertTrue(allowed);
        assertNull(UserHolder.getUser());
        assertFalse(new LoginInterceptor().preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
        verifyNoInteractions(redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisLookupFailureReturns503AndNeverAuthenticatesFromStaleThreadState() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("login:token:test-token"))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));
        UserDTO previous = new UserDTO();
        previous.setId(99L);
        UserHolder.saveUser(previous);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProtectedProbeController())
                .addInterceptors(new RefreshTokenInterceptor(redis), new LoginInterceptor())
                .setControllerAdvice(new com.hmdp.config.WebExceptionAdvice())
                .build();

        mvc.perform(get("/protected").header("authorization", "test-token"))
                .andExpect(status().isServiceUnavailable());

        assertNull(UserHolder.getUser());
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisExpiryFailureClearsResolvedIdentityAndReturns503() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("login:token:test-token")).thenReturn(java.util.Map.of("id", "7"));
        when(redis.expire(eq("login:token:test-token"), anyLong(), any()))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProtectedProbeController())
                .addInterceptors(new RefreshTokenInterceptor(redis), new LoginInterceptor())
                .setControllerAdvice(new com.hmdp.config.WebExceptionAdvice())
                .build();

        mvc.perform(get("/protected").header("authorization", "test-token"))
                .andExpect(status().isServiceUnavailable());

        assertNull(UserHolder.getUser());
    }

    @Test
    @SuppressWarnings("unchecked")
    void revokedTokenDuringLookupIsNotKeptAsCurrentIdentity() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("login:token:test-token")).thenReturn(java.util.Map.of("id", "7"));
        when(redis.expire(eq("login:token:test-token"), anyLong(), any())).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("authorization", "test-token");

        assertTrue(new RefreshTokenInterceptor(redis).preHandle(request, new MockHttpServletResponse(), new Object()));
        assertFalse(new LoginInterceptor().preHandle(request, new MockHttpServletResponse(), new Object()));
        assertNull(UserHolder.getUser());
    }

    @RestController
    static class ProtectedProbeController {
        @GetMapping("/protected")
        public String protectedResource() {
            return "protected";
        }
    }
}

package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpSession;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(OutputCaptureExtension.class)
class UserServiceImplTest {

    @Test
    void loginRejectsCodeAlreadyConsumedByAnotherRequest() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(0L);
        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("13686869696");
        form.setCode("123456");

        Result result = service.login(form, null);

        assertFalse(result.getSuccess());
        assertTrue(result.getErrorMsg().contains("验证码错误"));
    }

    @Test
    void logoutRemovesOnlyTheCurrentToken() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);

        Result result = service.logout("current-token");

        assertTrue(result.getSuccess());
        verify(redis).delete("login:token:current-token");
    }

    @Test
    void loginHashAndExpiryAreWrittenAtomically() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);

        service.saveLoginSession("new-token", Map.of("id", "7", "nickName", "guest"));

        ArgumentCaptor<DefaultRedisScript<Long>> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(script.capture(), eq(List.of("login:token:new-token")), arguments.capture());
        assertTrue(script.getValue().getScriptAsString().contains("redis.call('HSET'"));
        assertTrue(script.getValue().getScriptAsString().contains("redis.call('PEXPIRE'"));
        assertEquals(List.of("id", "7", "nickName", "guest", "2160000000").stream().sorted().toList(),
                java.util.Arrays.stream(arguments.getValue()).map(String::valueOf).sorted().toList());
        verify(redis, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void sendCodeDoesNotWriteTheVerificationCodeToLogs(CapturedOutput output) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        AtomicReference<String> writtenCode = new AtomicReference<>();
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(invocation -> {
            writtenCode.set(invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(values.get(anyString())).thenAnswer(invocation -> writtenCode.get());
        when(redis.getExpire(anyString(), any(TimeUnit.class))).thenReturn(120L);

        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);

        Result result = service.sendCode("13686869696", null);

        assertTrue(result.getSuccess());
        assertNull(result.getData(), "mock code must stay hidden unless explicitly enabled");
        assertTrue(output.getAll().contains("验证码发送成功"));
        assertFalse(output.getAll().contains(writtenCode.get()));
        assertFalse(output.getAll().contains("code="));
        assertFalse(output.getAll().contains("13686869696"));
        assertFalse(output.getAll().contains("login:code:"));
    }

    @Test
    void sendCodeReturnsMockCodeOnlyWhenExplicitlyEnabled() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        AtomicReference<String> writtenCode = new AtomicReference<>();
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(invocation -> {
            writtenCode.set(invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(values.get(anyString())).thenAnswer(invocation -> writtenCode.get());
        when(redis.getExpire(anyString(), any(TimeUnit.class))).thenReturn(120L);

        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "mockCodeEnabled", true);

        Result result = service.sendCode("13686869696", new MockHttpSession());

        assertTrue(result.getSuccess());
        assertEquals(writtenCode.get(), result.getData());
        assertTrue(String.valueOf(result.getData()).matches("\\d{6}"));
    }

    @Test
    void failedUserInsertStopsLoginInsteadOfCreatingSessionWithoutUserId() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.insert(any(User.class))).thenReturn(0);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(1L);
        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("13686869696");
        form.setCode("123456");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.login(form, null));
        verify(mapper).insert(any(User.class));
        verify(redis).execute(any(DefaultRedisScript.class), anyList(), any());
    }
}

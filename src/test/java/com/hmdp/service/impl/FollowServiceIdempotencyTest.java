package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.client.UserDirectory;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FollowServiceIdempotencyTest {
    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    @SuppressWarnings("unchecked")
    void repeatedFollowRequestsInsertOnceAndRepairTheRedisSet() {
        FollowMapper mapper = mock(FollowMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        AtomicLong rowCount = new AtomicLong();
        AtomicLong inserts = new AtomicLong();
        when(redisson.getLock("lock:follow:7:42")).thenReturn(lock);
        when(redis.opsForSet()).thenReturn(sets);
        when(mapper.selectCount(any(Wrapper.class))).thenAnswer(invocation -> rowCount.get());
        when(mapper.insert(any(Follow.class))).thenAnswer(invocation -> {
            rowCount.incrementAndGet();
            inserts.incrementAndGet();
            return 1;
        });

        FollowServiceImpl service = new FollowServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "redissonClient", redisson);
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);

        assertTrue(service.follow(42L, true).getSuccess());
        assertTrue(service.follow(42L, true).getSuccess());

        assertTrue(inserts.get() == 1);
        verify(sets, times(2)).add("follows:7", "42");
        verify(lock, times(2)).lock();
        verify(lock, times(2)).unlock();
    }

    @Test
    void cannotFollowSelf() {
        RedissonClient redisson = mock(RedissonClient.class);
        FollowServiceImpl service = new FollowServiceImpl();
        ReflectionTestUtils.setField(service, "redissonClient", redisson);
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);

        assertFalse(service.follow(7L, true).getSuccess());
        verifyNoInteractions(redisson);
    }

    @Test
    @SuppressWarnings("unchecked")
    void commonFollowsRemainAvailableWhenRedisSetsAreMissing() {
        FollowMapper mapper = mock(FollowMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        UserDirectory users = mock(UserDirectory.class);
        UserDTO commonUser = new UserDTO();
        commonUser.setId(11L);
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.intersect("follows:7", "follows:42")).thenReturn(Set.of());
        when(mapper.selectCommonFollowUserIds(7L, 42L)).thenReturn(List.of(11L));
        when(users.findByIds(List.of(11L))).thenReturn(List.of(commonUser));

        FollowServiceImpl service = new FollowServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "followMapper", mapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "userDirectory", users);
        UserDTO current = new UserDTO();
        current.setId(7L);
        UserHolder.saveUser(current);

        var result = service.followCommons(42L);

        assertTrue(result.getSuccess());
        assertTrue(result.getData() instanceof List<?> data && data.contains(commonUser));
        verify(mapper).selectCommonFollowUserIds(7L, 42L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void commonFollowsFallBackToDatabaseWhenRedisIsUnavailable() {
        FollowMapper mapper = mock(FollowMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        UserDirectory users = mock(UserDirectory.class);
        UserDTO commonUser = new UserDTO();
        commonUser.setId(11L);
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.intersect("follows:7", "follows:42"))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        when(mapper.selectCommonFollowUserIds(7L, 42L)).thenReturn(List.of(11L));
        when(users.findByIds(List.of(11L))).thenReturn(List.of(commonUser));

        FollowServiceImpl service = new FollowServiceImpl();
        ReflectionTestUtils.setField(service, "followMapper", mapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "userDirectory", users);
        UserDTO current = new UserDTO();
        current.setId(7L);
        UserHolder.saveUser(current);

        var result = service.followCommons(42L);

        assertTrue(result.getSuccess());
        assertTrue(result.getData() instanceof List<?> data && data.contains(commonUser));
        verify(mapper).selectCommonFollowUserIds(7L, 42L);
    }
}

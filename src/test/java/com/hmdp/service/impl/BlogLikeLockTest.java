package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlogLikeLockTest {
    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    void toggleIsSerializedByUserAndBlogBeforeReadingAndUpdatingLikeState() {
        BlogMapper mapper = mock(BlogMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> liked = mock(ZSetOperations.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        AtomicBoolean locked = new AtomicBoolean();

        when(redisson.getLock("lock:blog:like:42:7")).thenReturn(lock);
        when(redis.opsForZSet()).thenReturn(liked);
        when(liked.score("blog:liked:42", "7")).thenAnswer(new org.mockito.stubbing.Answer<Double>() {
            private int reads;

            @Override
            public Double answer(org.mockito.invocation.InvocationOnMock invocation) {
                assertTrue(locked.get(), "Redis like state must be read while holding the lock");
                return reads++ == 0 ? null : 1.0;
            }
        });
        doAnswer(invocation -> {
            locked.set(true);
            return null;
        }).when(lock).lock();
        doAnswer(invocation -> {
            assertTrue(locked.get(), "The lock must remain held until Redis is updated");
            locked.set(false);
            return null;
        }).when(lock).unlock();
        when(mapper.update(isNull(), any(Wrapper.class))).thenAnswer(invocation -> {
            assertTrue(locked.get(), "Database like count must be updated while holding the lock");
            return 1;
        });

        BlogServiceImpl service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "redissonClient", redisson);
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);

        service.likeBlog(42L);
        service.likeBlog(42L);

        verify(liked).add(eq("blog:liked:42"), eq("7"), org.mockito.ArgumentMatchers.anyDouble());
        verify(liked).remove("blog:liked:42", "7");
        ArgumentCaptor<Wrapper<Blog>> updateCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, org.mockito.Mockito.times(2)).update(isNull(), updateCaptor.capture());
        assertTrue(String.valueOf(ReflectionTestUtils.getField(updateCaptor.getAllValues().get(0), "sqlSet"))
                .contains("COALESCE(liked, 0)"));
        assertTrue(String.valueOf(ReflectionTestUtils.getField(updateCaptor.getAllValues().get(1), "sqlSet"))
                .contains("CASE WHEN liked IS NULL OR liked = 0"));
        assertTrue(!locked.get());
        var order = inOrder(lock, liked, mapper);
        order.verify(lock).lock();
        order.verify(liked).score("blog:liked:42", "7");
        order.verify(mapper).update(isNull(), any(Wrapper.class));
        order.verify(liked).add(eq("blog:liked:42"), eq("7"), org.mockito.ArgumentMatchers.anyDouble());
        order.verify(lock).unlock();
        order.verify(lock).lock();
        order.verify(liked).score("blog:liked:42", "7");
        order.verify(mapper).update(isNull(), any(Wrapper.class));
        order.verify(liked).remove("blog:liked:42", "7");
        order.verify(lock).unlock();
    }
}

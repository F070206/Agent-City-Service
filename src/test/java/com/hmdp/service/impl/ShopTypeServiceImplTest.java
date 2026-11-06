package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopTypeServiceImplTest {

    private static final String CACHE_KEY = "cache:shop:type:list:v1";

    @Test
    @SuppressWarnings("unchecked")
    void returnsCachedTypesWithoutQueryingDatabase() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(CACHE_KEY)).thenReturn("[{\"id\":1,\"name\":\"美食\",\"sort\":1}]");
        ShopTypeMapper mapper = mock(ShopTypeMapper.class);
        ShopTypeServiceImpl service = service(redis, mapper);

        List<ShopType> result = service.queryTypeList();

        assertEquals(1, result.size());
        assertEquals("美食", result.get(0).getName());
        verifyNoInteractions(mapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesSortedTypesAndCachesOnMiss() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(CACHE_KEY)).thenReturn(null);
        ShopTypeMapper mapper = mock(ShopTypeMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                new ShopType().setId(1L).setName("美食").setSort(1)));
        ShopTypeServiceImpl service = service(redis, mapper);

        List<ShopType> result = service.queryTypeList();

        assertEquals("美食", result.get(0).getName());
        verify(mapper).selectList(any(Wrapper.class));
        verify(values).set(eq(CACHE_KEY), anyString(), eq(Duration.ofMinutes(10)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackToDatabaseWhenRedisReadFails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(CACHE_KEY)).thenThrow(new IllegalStateException("Redis unavailable"));
        ShopTypeMapper mapper = mock(ShopTypeMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                new ShopType().setId(1L).setName("美食").setSort(1)));
        ShopTypeServiceImpl service = service(redis, mapper);

        assertEquals("美食", service.queryTypeList().get(0).getName());
        verify(mapper).selectList(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentColdCacheRequestsShareOneDatabaseRead() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        AtomicReference<String> cacheValue = new AtomicReference<>();
        when(values.get(CACHE_KEY)).thenAnswer(invocation -> cacheValue.get());
        doAnswer(invocation -> {
                    cacheValue.set(invocation.getArgument(1));
                    return null;
                }).when(values).set(eq(CACHE_KEY), anyString(), eq(Duration.ofMinutes(10)));
        ShopTypeMapper mapper = mock(ShopTypeMapper.class);
        AtomicInteger databaseReads = new AtomicInteger();
        when(mapper.selectList(any(Wrapper.class))).thenAnswer(invocation -> {
            databaseReads.incrementAndGet();
            Thread.sleep(100);
            return List.of(new ShopType().setId(1L).setName("美食").setSort(1));
        });
        ReentrantLock localLock = new ReentrantLock();
        RedissonClient redisson = mock(RedissonClient.class);
        RLock distributedLock = mock(RLock.class);
        when(redisson.getLock("lock:" + CACHE_KEY)).thenReturn(distributedLock);
        doAnswer(invocation -> { localLock.lock(); return null; }).when(distributedLock).lock();
        doAnswer(invocation -> { localLock.unlock(); return null; }).when(distributedLock).unlock();
        ShopTypeServiceImpl service = service(redis, mapper, redisson);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(service::queryTypeList);
            var second = executor.submit(service::queryTypeList);
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(1, databaseReads.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private ShopTypeServiceImpl service(StringRedisTemplate redis, ShopTypeMapper mapper) {
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock("lock:" + CACHE_KEY)).thenReturn(lock);
        return service(redis, mapper, redisson);
    }

    private ShopTypeServiceImpl service(StringRedisTemplate redis, ShopTypeMapper mapper, RedissonClient redisson) {
        ShopTypeServiceImpl service = new ShopTypeServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "redissonClient", redisson);
        return service;
    }
}

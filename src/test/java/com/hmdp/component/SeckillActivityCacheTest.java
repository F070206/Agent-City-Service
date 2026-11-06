package com.hmdp.component;

import com.hmdp.dto.SeckillVoucherLookup;
import com.hmdp.mapper.SeckillVoucherMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SeckillActivityCacheTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void activityWindowEpochUsesShanghaiBusinessZoneWhenJvmUsesUtc() {
        TimeZone originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            SeckillVoucherMapper mapper = mock(SeckillVoucherMapper.class);
            when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                    eq(java.util.List.of("seckill:activity:11")), any(Object[].class))).thenReturn(1L);
            SeckillVoucherLookup activity = activity(11L, 1L, 0);

            new SeckillActivityCache(redis, mapper).put(activity);

            org.mockito.ArgumentCaptor<Object[]> arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
            verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                    eq(java.util.List.of("seckill:activity:11")), arguments.capture());
            String expectedStart = Long.toString(activity.getBeginTime().atZone(ZoneId.of("Asia/Shanghai"))
                    .toInstant().toEpochMilli());
            assertEquals(expectedStart, arguments.getValue()[1]);
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void refreshFailureInvalidatesOldMetadataAndRetriesCommittedActivity() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SeckillVoucherMapper mapper = mock(SeckillVoucherMapper.class);
        SeckillActivityCache cache = new SeckillActivityCache(redis, mapper);
        SeckillVoucherLookup activity = new SeckillVoucherLookup();
        activity.setVoucherId(9L);
        activity.setBeginTime(LocalDateTime.of(2026, 11, 4, 10, 0));
        activity.setEndTime(LocalDateTime.of(2026, 11, 5, 10, 0));
        activity.setVoucherStatus(0);
        activity.setVoucherType(1);
        activity.setVersion(2L);
        when(mapper.findForSeckill(9L)).thenReturn(activity);
        doThrow(new IllegalStateException("Redis unavailable"))
                .doReturn(1L).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                        eq(java.util.List.of("seckill:activity:9")), any(Object[].class));
        when(redis.delete("seckill:activity:9")).thenReturn(true);

        cache.refreshAfterCommit(activity);

        verify(redis).delete("seckill:activity:9");
        cache.retryPendingRefreshes();
        verify(redis, times(2)).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                eq(java.util.List.of("seckill:activity:9")), any(Object[].class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void olderSuccessfulRefreshDoesNotClearPendingNewerVersion() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SeckillVoucherMapper mapper = mock(SeckillVoucherMapper.class);
        SeckillActivityCache cache = new SeckillActivityCache(redis, mapper);
        SeckillVoucherLookup newer = activity(3L, 3L, 0);
        SeckillVoucherLookup older = activity(3L, 2L, 1);
        when(mapper.findForSeckill(3L)).thenReturn(newer);
        doThrow(new IllegalStateException("Redis unavailable"))
                .doReturn(0L).doReturn(1L)
                .when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                        eq(java.util.List.of("seckill:activity:3")), any(Object[].class));

        cache.refreshAfterCommit(newer);
        cache.refreshAfterCommit(older);
        cache.retryPendingRefreshes();

        verify(mapper).findForSeckill(3L);
        verify(redis, times(3)).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                eq(java.util.List.of("seckill:activity:3")), any(Object[].class));
    }

    private SeckillVoucherLookup activity(Long voucherId, Long version, int status) {
        SeckillVoucherLookup activity = new SeckillVoucherLookup();
        activity.setVoucherId(voucherId);
        activity.setVersion(version);
        activity.setBeginTime(LocalDateTime.of(2026, 11, 4, 10, 0));
        activity.setEndTime(LocalDateTime.of(2026, 11, 5, 10, 0));
        activity.setVoucherStatus(status);
        activity.setVoucherType(1);
        return activity;
    }
}

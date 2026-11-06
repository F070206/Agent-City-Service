package com.hmdp.component;

import com.hmdp.entity.BlogFeedDispatch;
import com.hmdp.mapper.BlogFeedDispatchMapper;
import java.util.List;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BlogFeedDispatchWorkerTest {
    @Test
    void successfulLuaWriteMarksDispatchDelivered() {
        BlogFeedDispatchMapper mapper = mock(BlogFeedDispatchMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        BlogFeedDispatch task = task(10L, 4L);
        when(mapper.claim(any())).thenReturn(1);
        when(mapper.findClaimed(any())).thenReturn(List.of(task));
        when(mapper.deleteDeliveredBatch(anyList(), anyString())).thenReturn(1);
        when(redis.executePipelined(any(SessionCallback.class))).thenReturn(List.of(1000L));

        new BlogFeedDispatchWorker(mapper, redis).dispatchPending();

        verify(mapper).purgeDeliveredBatch();
        verify(mapper).deleteDeliveredBatch(argThat(tasks -> tasks.size() == 1
                && tasks.get(0).getBlogId().equals(10L)), any());
        verify(mapper, never()).recordFailures(any(), any());
    }

    @Test
    void redisCommandFailureIsPersistedForRetry() {
        BlogFeedDispatchMapper mapper = mock(BlogFeedDispatchMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        BlogFeedDispatch task = task(10L, 4L);
        when(mapper.claim(any())).thenReturn(1);
        when(mapper.findClaimed(any())).thenReturn(List.of(task));
        when(mapper.recordFailures(anyList(), anyString())).thenReturn(1);
        when(redis.executePipelined(any(SessionCallback.class)))
                .thenReturn(List.of(new IllegalStateException("Redis unavailable")));

        assertDoesNotThrow(() -> new BlogFeedDispatchWorker(mapper, redis).dispatchPending());

        verify(mapper).purgeDeliveredBatch();
        verify(mapper).recordFailures(argThat(tasks -> tasks.size() == 1
                && tasks.get(0).getFollowerId().equals(4L)), any());
        verify(mapper, never()).deleteDeliveredBatch(any(), any());
    }

    @Test
    void mixedPipelineResultsAreAcknowledgedAndDeferredInTwoBatchWrites() {
        BlogFeedDispatchMapper mapper = mock(BlogFeedDispatchMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        BlogFeedDispatch delivered = task(10L, 4L);
        BlogFeedDispatch failed = task(11L, 5L);
        when(mapper.claim(any())).thenReturn(2);
        when(mapper.findClaimed(any())).thenReturn(List.of(delivered, failed));
        when(mapper.deleteDeliveredBatch(anyList(), anyString())).thenReturn(1);
        when(mapper.recordFailures(anyList(), anyString())).thenReturn(1);
        when(redis.executePipelined(any(SessionCallback.class)))
                .thenReturn(List.of(1000L, new IllegalStateException("Redis command failed")));

        new BlogFeedDispatchWorker(mapper, redis).dispatchPending();

        verify(mapper).deleteDeliveredBatch(argThat(tasks -> tasks.equals(List.of(delivered))), anyString());
        verify(mapper).recordFailures(argThat(tasks -> tasks.equals(List.of(failed))), anyString());
    }

    @Test
    void pipelineQueuesOneIdempotentFeedWritePerClaimedRecipient() {
        BlogFeedDispatchMapper mapper = mock(BlogFeedDispatchMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(mapper.claim(any())).thenReturn(1);
        when(mapper.findClaimed(any())).thenReturn(List.of(task(10L, 4L), task(11L, 5L)));
        when(redis.executePipelined(any(SessionCallback.class))).thenAnswer(invocation -> {
            SessionCallback<?> callback = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            RedisOperations<String, String> operations = mock(RedisOperations.class);
            callback.execute(operations);
            String score = Long.toString(LocalDateTime.of(2026, 11, 4, 12, 0)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            verify(operations).execute(any(RedisScript.class), eq(List.of("feed:4")),
                    eq(score), eq("10"), eq("1000"));
            verify(operations).execute(any(RedisScript.class), eq(List.of("feed:5")),
                    eq(score), eq("11"), eq("1000"));
            return List.of(1000L, 1000L);
        });

        new BlogFeedDispatchWorker(mapper, redis).dispatchPending();
    }

    private BlogFeedDispatch task(long blogId, long followerId) {
        BlogFeedDispatch task = new BlogFeedDispatch();
        task.setBlogId(blogId);
        task.setFollowerId(followerId);
        task.setBlogCreatedAt(LocalDateTime.of(2026, 11, 4, 12, 0));
        return task;
    }
}

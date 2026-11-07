package com.hmdp.component;

import com.hmdp.config.RocketMQConstants;
import com.hmdp.entity.SeckillOrderDispatch;
import com.hmdp.mapper.SeckillOrderDispatchMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.time.LocalDateTime;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SeckillOrderDispatchWorkerTest {
    @Test
    void retriesUncompletedOrderUsingStoredPayload() {
        SeckillOrderDispatchMapper mapper = mock(SeckillOrderDispatchMapper.class);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        StringRedisTemplate redis = redisWithoutRecoveryEntries();
        SeckillOrderDispatch task = new SeckillOrderDispatch();
        task.setOrderId(1L);
        task.setPayload("{\"id\":\"1\"}");
        when(mq.syncSend(RocketMQConstants.SECKILL_ORDER_DESTINATION, task.getPayload())).thenReturn(sendOk());
        when(mapper.findClaimed(anyString())).thenReturn(List.of(task));

        new SeckillOrderDispatchWorker(mapper, mq, redis, new ObjectMapper()).retryPending();

        verify(mq).syncSend(RocketMQConstants.SECKILL_ORDER_DESTINATION, task.getPayload());
        verify(mapper).claimPending(anyString());
        verify(mapper).recordAttempt(eq(1L), anyString());
    }

    @Test
    void restoresMissingDatabaseTaskFromAtomicRedisRecoveryEntry() {
        SeckillOrderDispatchMapper mapper = mock(SeckillOrderDispatchMapper.class);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForValue()).thenReturn(values);
        when(zset.range("seckill:dispatch:recovery", 0, 499)).thenReturn(Set.of("42"));
        when(values.multiGet(List.of("seckill:dispatch:payload:42")))
                .thenReturn(List.of("{\"id\":\"42\",\"userId\":7,\"voucherId\":10,"
                        + "\"createTime\":\"2026-11-04T12:00:00\"}"));
        LocalDateTime orderCreatedAt = LocalDateTime.of(2026, 11, 4, 12, 0);
        String payload = "{\"id\":\"42\",\"userId\":7,\"voucherId\":10,"
                + "\"createTime\":\"2026-11-04T12:00:00\"}";
        when(mq.syncSend(RocketMQConstants.SECKILL_ORDER_DESTINATION, payload)).thenReturn(sendOk());
        SeckillOrderDispatch recovered = new SeckillOrderDispatch();
        recovered.setOrderId(42L);
        recovered.setPayload(payload);
        recovered.setCompleted(0);
        when(mapper.findCompletionStatuses(anyList())).thenReturn(Collections.emptyList(), List.of(recovered));
        when(mapper.insertRecoveredTasks(anyList())).thenReturn(1);
        when(mapper.findClaimed(anyString())).thenReturn(List.of(recovered));

        new SeckillOrderDispatchWorker(mapper, mq, redis, objectMapper()).retryPending();

        org.mockito.ArgumentCaptor<List<SeckillOrderDispatch>> tasks = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(mapper).insertRecoveredTasks(tasks.capture());
        assertEquals(42L, tasks.getValue().get(0).getOrderId());
        assertEquals(7L, tasks.getValue().get(0).getUserId());
        assertEquals(payload, tasks.getValue().get(0).getPayload());
        assertEquals(orderCreatedAt.plusMinutes(15), tasks.getValue().get(0).getTimeoutRetryTime());
        verify(redis).delete(List.of("seckill:dispatch:payload:42"));
        verify(zset).remove("seckill:dispatch:recovery", "42");
        verify(mq).syncSend(RocketMQConstants.SECKILL_ORDER_DESTINATION, payload);
        verify(mapper).recordAttempt(eq(42L), anyString());
    }

    @Test
    void retriesDurableDatabaseTasksWhenRedisRecoveryQueueIsUnavailable() {
        SeckillOrderDispatchMapper mapper = mock(SeckillOrderDispatchMapper.class);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForZSet()).thenThrow(new RedisConnectionFailureException("Redis unavailable"));
        SeckillOrderDispatch task = new SeckillOrderDispatch();
        task.setOrderId(77L);
        task.setPayload("{\"id\":\"77\"}");
        when(mq.syncSend(RocketMQConstants.SECKILL_ORDER_DESTINATION, task.getPayload())).thenReturn(sendOk());
        when(mapper.findClaimed(anyString())).thenReturn(List.of(task));

        new SeckillOrderDispatchWorker(mapper, mq, redis, objectMapper()).retryPending();

        verify(mapper).claimPending(anyString());
        verify(mq).syncSend(RocketMQConstants.SECKILL_ORDER_DESTINATION, task.getPayload());
        verify(mapper).recordAttempt(eq(77L), anyString());
    }

    @Test
    void defersRecoveryRecordWhenDatabaseCannotRestoreItsDispatchTask() {
        SeckillOrderDispatchMapper mapper = mock(SeckillOrderDispatchMapper.class);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForValue()).thenReturn(values);
        when(zset.range("seckill:dispatch:recovery", 0, 499)).thenReturn(Set.of("42"));
        when(mapper.findCompletionStatuses(anyList())).thenReturn(Collections.emptyList());
        when(mapper.insertRecoveredTasks(anyList()))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(mapper.findClaimed(anyString())).thenReturn(Collections.emptyList());

        new SeckillOrderDispatchWorker(mapper, mq, redis, new ObjectMapper()).retryPending();

        verify(zset).incrementScore("seckill:dispatch:recovery", "42", 1);
        verifyNoInteractions(mq);
    }

    @Test
    void defersRecoveryRecordWhenItsPayloadIsMissing() {
        SeckillOrderDispatchMapper mapper = mock(SeckillOrderDispatchMapper.class);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForValue()).thenReturn(values);
        when(zset.range("seckill:dispatch:recovery", 0, 499)).thenReturn(Set.of("43"));
        when(mapper.findCompletionStatuses(anyList())).thenReturn(Collections.emptyList());
        when(values.multiGet(List.of("seckill:dispatch:payload:43"))).thenReturn(Collections.singletonList(null));
        when(mapper.findClaimed(anyString())).thenReturn(Collections.emptyList());

        new SeckillOrderDispatchWorker(mapper, mq, redis, new ObjectMapper()).retryPending();

        verify(zset).incrementScore("seckill:dispatch:recovery", "43", 1);
        verifyNoInteractions(mq);
    }

    @Test
    void defersRecoveryRecordWhenPayloadOrderIdDoesNotMatchQueueEntry() {
        SeckillOrderDispatchMapper mapper = mock(SeckillOrderDispatchMapper.class);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForValue()).thenReturn(values);
        when(zset.range("seckill:dispatch:recovery", 0, 499)).thenReturn(Set.of("44"));
        when(mapper.findCompletionStatuses(anyList())).thenReturn(Collections.emptyList());
        when(values.multiGet(List.of("seckill:dispatch:payload:44")))
                .thenReturn(List.of("{\"id\":\"45\",\"userId\":7,\"voucherId\":10}"));
        when(mapper.findClaimed(anyString())).thenReturn(Collections.emptyList());

        new SeckillOrderDispatchWorker(mapper, mq, redis, new ObjectMapper()).retryPending();

        verify(zset).incrementScore("seckill:dispatch:recovery", "44", 1);
        verifyNoInteractions(mq);
    }

    @Test
    void defersRecoveryRecordWhenPayloadHasNoVoucherId() {
        SeckillOrderDispatchMapper mapper = mock(SeckillOrderDispatchMapper.class);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForValue()).thenReturn(values);
        when(zset.range("seckill:dispatch:recovery", 0, 499)).thenReturn(Set.of("46"));
        when(mapper.findCompletionStatuses(anyList())).thenReturn(Collections.emptyList());
        when(values.multiGet(List.of("seckill:dispatch:payload:46")))
                .thenReturn(List.of("{\"id\":\"46\",\"userId\":7,"
                        + "\"createTime\":\"2026-11-04T12:00:00\"}"));

        new SeckillOrderDispatchWorker(mapper, mq, redis, objectMapper()).retryPending();

        verify(zset).incrementScore("seckill:dispatch:recovery", "46", 1);
        verify(mapper, never()).insertRecoveredTasks(anyList());
        verifyNoInteractions(mq);
    }

    @Test
    void sendsDueTimeoutMessageFromPersistentTaskAndMarksItSent() {
        SeckillOrderDispatchMapper mapper = mock(SeckillOrderDispatchMapper.class);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        when(mapper.findClaimed(anyString())).thenReturn(Collections.emptyList());
        when(mapper.claimTimeoutPending(anyString())).thenReturn(1);
        when(mapper.findTimeoutClaimed(anyString())).thenReturn(List.of(42L));
        when(mq.syncSend(RocketMQConstants.ORDER_TIMEOUT_DESTINATION, "42")).thenReturn(sendOk());

        new SeckillOrderDispatchWorker(mapper, mq, redisWithoutRecoveryEntries(), objectMapper()).retryPending();

        verify(mq).syncSend(RocketMQConstants.ORDER_TIMEOUT_DESTINATION, "42");
        verify(mapper).markTimeoutSent(eq(42L), anyString());
        verify(mapper, never()).recordTimeoutAttempt(anyLong(), anyString());
    }

    @Test
    void retriesFailedTimeoutMessageWithPersistentBackoff() {
        SeckillOrderDispatchMapper mapper = mock(SeckillOrderDispatchMapper.class);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        when(mapper.findClaimed(anyString())).thenReturn(Collections.emptyList());
        when(mapper.claimTimeoutPending(anyString())).thenReturn(1);
        when(mapper.findTimeoutClaimed(anyString())).thenReturn(List.of(42L));
        doThrow(new IllegalStateException("broker unavailable"))
                .when(mq).syncSend(RocketMQConstants.ORDER_TIMEOUT_DESTINATION, "42");

        new SeckillOrderDispatchWorker(mapper, mq, redisWithoutRecoveryEntries(), objectMapper()).retryPending();

        verify(mapper).recordTimeoutAttempt(eq(42L), anyString());
        verify(mapper, never()).markTimeoutSent(anyLong(), anyString());
    }

    @Test
    void nonOkTimeoutSendResultIsRetriedInsteadOfMarkedSent() {
        SeckillOrderDispatchMapper mapper = mock(SeckillOrderDispatchMapper.class);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        when(mapper.findClaimed(anyString())).thenReturn(Collections.emptyList());
        when(mapper.claimTimeoutPending(anyString())).thenReturn(1);
        when(mapper.findTimeoutClaimed(anyString())).thenReturn(List.of(42L));
        when(mq.syncSend(RocketMQConstants.ORDER_TIMEOUT_DESTINATION, "42"))
                .thenReturn(new SendResult(SendStatus.SLAVE_NOT_AVAILABLE, "message-id", "offset-id", null, 0));

        new SeckillOrderDispatchWorker(mapper, mq, redisWithoutRecoveryEntries(), objectMapper()).retryPending();

        verify(mapper).recordTimeoutAttempt(eq(42L), anyString());
        verify(mapper, never()).markTimeoutSent(anyLong(), anyString());
    }

    @Test
    void nonOkOrderSendResultLeavesOutboxPendingForRetry() {
        SeckillOrderDispatchMapper mapper = mock(SeckillOrderDispatchMapper.class);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        SeckillOrderDispatch task = new SeckillOrderDispatch();
        task.setOrderId(43L);
        task.setPayload("payload");
        when(mapper.findClaimed(anyString())).thenReturn(List.of(task));
        when(mq.syncSend(RocketMQConstants.SECKILL_ORDER_DESTINATION, "payload"))
                .thenReturn(new SendResult(SendStatus.FLUSH_DISK_TIMEOUT, "message-id", "offset-id", null, 0));

        new SeckillOrderDispatchWorker(mapper, mq, redisWithoutRecoveryEntries(), objectMapper()).retryPending();

        verify(mapper).recordAttempt(eq(43L), anyString());
        verify(mapper, never()).markCompleted(anyLong());
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private SendResult sendOk() {
        return new SendResult(SendStatus.SEND_OK, "message-id", "offset-id", null, 0);
    }

    private StringRedisTemplate redisWithoutRecoveryEntries() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.range("seckill:dispatch:recovery", 0, 499)).thenReturn(Collections.emptySet());
        return redis;
    }
}

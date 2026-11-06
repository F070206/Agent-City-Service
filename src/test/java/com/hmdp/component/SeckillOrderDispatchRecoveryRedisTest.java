package com.hmdp.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.RocketMQConstants;
import com.hmdp.entity.SeckillOrderDispatch;
import com.hmdp.mapper.SeckillOrderDispatchMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_REDIS_HOST", matches = ".+")
class SeckillOrderDispatchRecoveryRedisTest {
    private static final String RECOVERY_KEY = "seckill:dispatch:recovery";

    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private List<String> orderIds;

    @BeforeEach
    void setUp() {
        connection = new LettuceConnectionFactory(System.getenv("PAYMENT_TEST_REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("PAYMENT_TEST_REDIS_PORT", "6379")));
        String password = System.getenv().getOrDefault("PAYMENT_TEST_REDIS_PASSWORD", "");
        if (!password.isEmpty()) {
            connection.getStandaloneConfiguration().setPassword(password);
        }
        connection.getStandaloneConfiguration().setDatabase(Integer.parseInt(
                System.getenv().getOrDefault("PAYMENT_TEST_REDIS_DATABASE", "0")));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);

        long firstId = ThreadLocalRandom.current().nextLong(1_000_000_000_000_000_000L,
                8_000_000_000_000_000_000L);
        orderIds = new ArrayList<>(501);
        for (int i = 0; i <= 500; i++) {
            String orderId = Long.toString(firstId + i);
            orderIds.add(orderId);
            redis.opsForZSet().add(RECOVERY_KEY, orderId, 0);
            if (i < 500) {
                redis.opsForValue().set("seckill:dispatch:payload:" + orderId,
                        "{\"id\":\"" + (firstId + i + 10_000) + "\",\"userId\":7,\"voucherId\":10,"
                                + "\"createTime\":\"2026-11-04T12:00:00\"}");
            }
        }
        String validOrderId = orderIds.get(500);
        redis.opsForValue().set("seckill:dispatch:payload:" + validOrderId,
                "{\"id\":\"" + validOrderId + "\",\"userId\":7,\"voucherId\":10,"
                        + "\"createTime\":\"2026-11-04T12:00:00\"}");
    }

    @AfterEach
    void cleanUp() {
        try {
            if (redis != null && orderIds != null) {
                for (String orderId : orderIds) {
                    redis.delete("seckill:dispatch:payload:" + orderId);
                    redis.opsForZSet().remove(RECOVERY_KEY, orderId);
                }
            }
        } finally {
            if (connection != null) {
                connection.destroy();
            }
        }
    }

    @Test
    void badFirstFiveHundredRecoveryEntriesDoNotStarveTheNextValidEntry() {
        SeckillOrderDispatchMapper mapper = mock(SeckillOrderDispatchMapper.class);
        when(mapper.findCompletionStatuses(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(), List.of(), List.of(persistedTask()));
        when(mapper.insertRecoveredTasks(org.mockito.ArgumentMatchers.anyList())).thenReturn(1);
        when(mapper.findClaimed(anyString())).thenReturn(List.of());
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        SeckillOrderDispatchWorker worker = new SeckillOrderDispatchWorker(mapper, mq, redis,
                new ObjectMapper().findAndRegisterModules());

        worker.retryPending();

        assertEquals(orderIds.get(500), redis.opsForZSet().range(RECOVERY_KEY, 0, 0).iterator().next());

        worker.retryPending();

        verify(mapper).insertRecoveredTasks(org.mockito.ArgumentMatchers.argThat(tasks -> tasks.size() == 1
                && tasks.get(0).getOrderId().equals(Long.valueOf(orderIds.get(500)))));
        assertFalse(redis.opsForZSet().range(RECOVERY_KEY, 0, -1).contains(orderIds.get(500)));
        verify(mq, org.mockito.Mockito.never()).syncSend(eq(RocketMQConstants.SECKILL_ORDER_DESTINATION),
                anyString());
    }

    private SeckillOrderDispatch persistedTask() {
        SeckillOrderDispatch task = new SeckillOrderDispatch();
        task.setOrderId(Long.valueOf(orderIds.get(500)));
        task.setCompleted(0);
        return task;
    }
}

package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillOrderDispatchMapper;
import com.hmdp.mapper.SeckillOrderGuardMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_REDIS_HOST", matches = ".+")
class SeckillDuplicateReservationRedisTest {
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private String voucherId;
    private String orderId;
    private String orderKey;
    private String stockKey;
    private String pendingKey;

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
        voucherId = Long.toString(ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE));
        orderId = Long.toString(ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE));
        orderKey = "seckill:order:" + voucherId;
        stockKey = "seckill:stock:" + voucherId;
        pendingKey = "seckill:order:pending:" + orderId;
    }

    @AfterEach
    void cleanUp() {
        try {
            if (redis != null && stockKey != null) {
                redis.delete(List.of(stockKey, orderKey, pendingKey,
                        "seckill:dispatch:payload:" + orderId,
                        "seckill:stock:duplicate-rejected:" + orderId,
                        "seckill:order:reservation:" + voucherId));
                redis.opsForZSet().remove("seckill:dispatch:recovery", orderId);
            }
        } finally {
            if (connection != null) {
                connection.destroy();
            }
        }
    }

    @Test
    void databaseDuplicateRestoresRedisReservationWithoutRemovingExistingUserGuard() {
        redis.opsForValue().set(stockKey, "0");
        // Simulate the per-voucher user set losing an existing member while retaining inventory.
        redis.opsForValue().set(pendingKey, "7");

        VoucherOrderMapper orderMapper = mock(VoucherOrderMapper.class);
        Long orderIdValue = Long.valueOf(orderId);
        Long voucherIdValue = Long.valueOf(voucherId);
        when(orderMapper.selectById(orderIdValue)).thenReturn(null);
        SeckillOrderGuardMapper guard = mock(SeckillOrderGuardMapper.class);
        when(guard.reserve(7L, voucherIdValue, orderIdValue)).thenReturn(0);
        when(guard.findActiveOrderIdForUpdate(7L, voucherIdValue)).thenReturn(999L);
        SeckillOrderDispatchMapper dispatch = mock(SeckillOrderDispatchMapper.class);
        when(dispatch.markCompleted(orderIdValue)).thenReturn(1, 0);
        when(dispatch.findCompletionStatus(orderIdValue)).thenReturn(1);

        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", orderMapper);
        ReflectionTestUtils.setField(service, "seckillOrderGuardMapper", guard);
        ReflectionTestUtils.setField(service, "seckillOrderDispatchMapper", dispatch);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        VoucherOrder duplicate = new VoucherOrder();
        duplicate.setId(orderIdValue);
        duplicate.setUserId(7L);
        duplicate.setVoucherId(voucherIdValue);

        service.createVoucherOrder(duplicate);
        service.createVoucherOrder(duplicate);

        assertEquals("1", redis.opsForValue().get(stockKey));
        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(orderKey, "7")));
        assertEquals(null, redis.opsForValue().get(pendingKey));
        verify(dispatch, org.mockito.Mockito.times(2)).markCompleted(orderIdValue);
    }

    @Test
    void rejectedReservationRefundScriptIsIdempotentForTheSameOrder() {
        String refundKey = "seckill:stock:duplicate-rejected:" + orderId;
        String reservationKey = "seckill:order:reservation:" + voucherId;
        redis.opsForValue().set(stockKey, "0");
        redis.opsForValue().set(pendingKey, "7");
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("reject-duplicate-seckill-reservation.lua"));
        script.setResultType(Long.class);

        Long first = redis.execute(script, List.of(stockKey, orderKey, refundKey, pendingKey, reservationKey),
                "7", "active-order");
        Long retry = redis.execute(script, List.of(stockKey, orderKey, refundKey, pendingKey, reservationKey),
                "7", "active-order");

        assertEquals(1L, first);
        assertEquals(0L, retry);
        assertEquals("1", redis.opsForValue().get(stockKey));
        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(orderKey, "7")));
        assertEquals("active-order", redis.<String, String>opsForHash().get(reservationKey, "7"));
    }
}

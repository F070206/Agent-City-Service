package com.hmdp.component;

import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.OrderTimeoutRetryMapper;
import com.hmdp.service.IVoucherOrderService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "ORDER_RECOVERY_TEST_REDIS_HOST", matches = ".+")
class OrderTimeoutRecoveryWorkerRedisTest {
    private static RedissonClient firstRedis;
    private static RedissonClient secondRedis;

    @BeforeAll
    static void connectToIsolatedTestRedis() {
        firstRedis = createClient();
        secondRedis = createClient();
    }

    @AfterAll
    static void closeClients() {
        if (firstRedis != null) firstRedis.shutdown();
        if (secondRedis != null) secondRedis.shutdown();
    }

    @Test
    void independentInstancesDoNotScanAtTheSameTime() throws Exception {
        String lockName = "test:order:timeout-recovery:" + UUID.randomUUID();
        VoucherOrderMapper firstMapper = mock(VoucherOrderMapper.class);
        IVoucherOrderService firstOrders = mock(IVoucherOrderService.class);
        VoucherOrderMapper secondMapper = mock(VoucherOrderMapper.class);
        IVoucherOrderService secondOrders = mock(IVoucherOrderService.class);
        CountDownLatch firstScanStarted = new CountDownLatch(1);
        CountDownLatch allowFirstScanToFinish = new CountDownLatch(1);
        when(firstMapper.findExpiredSeckillOrderIds(any(LocalDateTime.class), anyLong(), eq(100)))
                .thenAnswer(invocation -> {
                    firstScanStarted.countDown();
                    assertTrue(allowFirstScanToFinish.await(5, TimeUnit.SECONDS));
                    return List.of();
                });

        OrderTimeoutRecoveryWorker first = worker(firstMapper, firstOrders, firstRedis, lockName);
        OrderTimeoutRecoveryWorker second = worker(secondMapper, secondOrders, secondRedis, lockName);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> scan = executor.submit(first::closeExpiredOrders);
            assertTrue(firstScanStarted.await(5, TimeUnit.SECONDS));

            second.closeExpiredOrders();
            verifyNoInteractions(secondMapper, secondOrders);

            allowFirstScanToFinish.countDown();
            scan.get(5, TimeUnit.SECONDS);
            verify(firstMapper).findExpiredSeckillOrderIds(any(LocalDateTime.class), eq(0L), eq(100));

            second.closeExpiredOrders();
            verify(secondMapper).findExpiredSeckillOrderIds(any(LocalDateTime.class), eq(0L), eq(100));
        } finally {
            allowFirstScanToFinish.countDown();
            executor.shutdownNow();
            firstRedis.getLock(lockName).forceUnlock();
        }
    }

    private static OrderTimeoutRecoveryWorker worker(VoucherOrderMapper mapper, IVoucherOrderService orders,
            RedissonClient redis, String lockName) {
        OrderTimeoutRecoveryWorker worker = new OrderTimeoutRecoveryWorker(mapper, orders,
                mock(OrderTimeoutRetryMapper.class), redis);
        ReflectionTestUtils.setField(worker, "lockName", lockName);
        return worker;
    }

    private static RedissonClient createClient() {
        Config config = new Config();
        String address = "redis://" + System.getenv("ORDER_RECOVERY_TEST_REDIS_HOST") + ":"
                + System.getenv().getOrDefault("ORDER_RECOVERY_TEST_REDIS_PORT", "6379");
        config.useSingleServer().setAddress(address)
                .setDatabase(Integer.parseInt(System.getenv().getOrDefault("ORDER_RECOVERY_TEST_REDIS_DATABASE", "15")));
        String password = System.getenv("ORDER_RECOVERY_TEST_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) {
            config.useSingleServer().setPassword(password);
        }
        return Redisson.create(config);
    }
}

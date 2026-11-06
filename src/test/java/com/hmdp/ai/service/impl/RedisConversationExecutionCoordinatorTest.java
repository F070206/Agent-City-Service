package com.hmdp.ai.service.impl;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CACHE_TEST_REDIS_HOST", matches = ".+")
class RedisConversationExecutionCoordinatorTest {
    private RedissonClient firstClient;
    private RedissonClient secondClient;

    @BeforeEach
    void setUp() {
        String address = "redis://" + System.getenv("CACHE_TEST_REDIS_HOST") + ":"
                + System.getenv().getOrDefault("CACHE_TEST_REDIS_PORT", "6379");
        Config config = new Config();
        config.useSingleServer().setAddress(address);
        firstClient = Redisson.create(config);
        secondClient = Redisson.create(config);
    }

    @AfterEach
    void cleanUp() {
        if (firstClient != null) {
            firstClient.shutdown();
        }
        if (secondClient != null) {
            secondClient.shutdown();
        }
    }

    @Test
    void sameConversationIsSerializedAcrossInstances() throws Exception {
        long conversationId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        RedisConversationExecutionCoordinator first = new RedisConversationExecutionCoordinator(firstClient);
        RedisConversationExecutionCoordinator second = new RedisConversationExecutionCoordinator(secondClient);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstTask = executor.submit(() -> first.runSerial(conversationId, () -> {
                firstEntered.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("first task did not resume");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            Future<?> secondTask = executor.submit(() -> second.runSerial(conversationId, secondEntered::countDown));
            assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            firstTask.get(5, TimeUnit.SECONDS);
            secondTask.get(5, TimeUnit.SECONDS);
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }
}

package com.hmdp.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAsyncConfigurationTest {
    @Test
    void leaseRenewalSchedulerHasWorkersForConcurrentHeartbeatTasks() throws InterruptedException {
        ThreadPoolTaskScheduler scheduler = new AiAsyncConfiguration().aiTaskLeaseScheduler();
        CountDownLatch blockingRenewalStarted = new CountDownLatch(1);
        CountDownLatch releaseBlockingRenewal = new CountDownLatch(1);
        CountDownLatch independentRenewalCompleted = new CountDownLatch(1);
        try {
            scheduler.initialize();
            assertEquals(4, scheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
            assertEquals("ai-task-lease-", scheduler.getThreadNamePrefix());

            scheduler.scheduleAtFixedRate(() -> {
                blockingRenewalStarted.countDown();
                try {
                    releaseBlockingRenewal.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }, Instant.now(), Duration.ofDays(1));
            assertTrue(blockingRenewalStarted.await(2, TimeUnit.SECONDS));
            scheduler.scheduleAtFixedRate(independentRenewalCompleted::countDown,
                    Instant.now(), Duration.ofDays(1));

            assertTrue(independentRenewalCompleted.await(2, TimeUnit.SECONDS));
        } finally {
            releaseBlockingRenewal.countDown();
            scheduler.shutdown();
        }
    }
}

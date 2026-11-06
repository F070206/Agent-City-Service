package com.hmdp.ai.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAgentTaskExecutorMetricsTest {
    @Test
    void reportsWorkerQueueAndRejectedTaskMetrics() throws InterruptedException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolTaskExecutor executor = new AiAsyncConfiguration().aiAgentTaskExecutor(registry);
        CountDownLatch workersStarted = new CountDownLatch(8);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        try {
            Runnable blockingTask = () -> {
                workersStarted.countDown();
                try {
                    releaseWorkers.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            };

            for (int i = 0; i < 4; i++) {
                executor.execute(blockingTask);
            }
            for (int i = 0; i < 200; i++) {
                executor.execute(() -> { });
            }
            for (int i = 0; i < 4; i++) {
                executor.execute(blockingTask);
            }
            assertTrue(workersStarted.await(2, TimeUnit.SECONDS));

            assertEquals(8, registry.get("cityai.ai.agent.executor.active").gauge().value());
            assertEquals(8, registry.get("cityai.ai.agent.executor.pool.size").gauge().value());
            assertEquals(200, registry.get("cityai.ai.agent.executor.queued").gauge().value());
            assertEquals(0, registry.get("cityai.ai.agent.executor.queue.remaining").gauge().value());
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
            assertEquals(1, registry.get("cityai.ai.agent.executor.rejected").counter().count());
        } finally {
            releaseWorkers.countDown();
            executor.shutdown();
            registry.close();
        }
    }
}

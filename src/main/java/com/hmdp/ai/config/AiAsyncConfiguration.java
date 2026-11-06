package com.hmdp.ai.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.RejectedExecutionException;

@Configuration
public class AiAsyncConfiguration {
    private static final int AI_TASK_LEASE_SCHEDULER_POOL_SIZE = 4;

    @Bean("aiAgentTaskExecutor")
    public ThreadPoolTaskExecutor aiAgentTaskExecutor(MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("ai-agent-");
        Counter rejected = Counter.builder("cityai.ai.agent.executor.rejected")
                .description("AI agent tasks rejected because its worker queue is full or shutting down")
                .register(meterRegistry);
        executor.setRejectedExecutionHandler((task, threadPool) -> {
            rejected.increment();
            throw new RejectedExecutionException("AI agent executor rejected a task");
        });
        executor.initialize();
        Gauge.builder("cityai.ai.agent.executor.active", executor, ThreadPoolTaskExecutor::getActiveCount)
                .description("AI agent executor threads currently processing tasks")
                .register(meterRegistry);
        Gauge.builder("cityai.ai.agent.executor.queued", executor,
                        pool -> pool.getThreadPoolExecutor().getQueue().size())
                .description("AI agent tasks currently waiting in the executor queue")
                .register(meterRegistry);
        Gauge.builder("cityai.ai.agent.executor.queue.remaining", executor,
                        pool -> pool.getThreadPoolExecutor().getQueue().remainingCapacity())
                .description("Remaining capacity of the AI agent task queue")
                .register(meterRegistry);
        Gauge.builder("cityai.ai.agent.executor.pool.size", executor, ThreadPoolTaskExecutor::getPoolSize)
                .description("Current AI agent executor pool size")
                .register(meterRegistry);
        return executor;
    }

    @Bean("aiTaskLeaseScheduler")
    public ThreadPoolTaskScheduler aiTaskLeaseScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(AI_TASK_LEASE_SCHEDULER_POOL_SIZE);
        scheduler.setThreadNamePrefix("ai-task-lease-");
        return scheduler;
    }
}

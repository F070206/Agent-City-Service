package com.hmdp.ai.service.impl;

import com.hmdp.ai.mapper.AiTaskMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AiTaskLeaseKeeper {
    private static final Duration RENEW_INTERVAL = Duration.ofMinutes(1);

    private final AiTaskMapper tasks;
    private final TaskScheduler scheduler;

    public AiTaskLeaseKeeper(AiTaskMapper tasks,
                             @Qualifier("aiTaskLeaseScheduler") TaskScheduler scheduler) {
        this.tasks = tasks;
        this.scheduler = scheduler;
    }

    public AiTaskLease start(Long messageId, String token) {
        AiTaskLease lease = new AiTaskLease(messageId);
        ScheduledFuture<?> renewal = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (tasks.renewLease(messageId, token) != 1) {
                    log.warn("AI 任务租约已不属于当前执行，停止旧任务，messageId={}", messageId);
                    lease.markLost();
                }
            } catch (Exception ex) {
                log.warn("AI 任务续租失败，停止旧任务并等待租约过期恢复，messageId={}", messageId, ex);
                lease.markLost();
            }
        }, Instant.now().plus(RENEW_INTERVAL), RENEW_INTERVAL);
        if (renewal == null) {
            throw new IllegalStateException("AI 任务续租调度失败");
        }
        lease.setRenewal(renewal);
        return lease;
    }
}

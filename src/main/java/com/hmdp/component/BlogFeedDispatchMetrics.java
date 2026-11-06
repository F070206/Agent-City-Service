package com.hmdp.component;

import com.hmdp.entity.BlogFeedDispatchBacklog;
import com.hmdp.mapper.BlogFeedDispatchMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BlogFeedDispatchMetrics {
    private final BlogFeedDispatchMapper mapper;
    private final MeterRegistry registry;
    @Value("${blog.feed.dispatch.metrics.repeated-failure-threshold:3}")
    private int failureThreshold = 3;
    private volatile double pending = Double.NaN;
    private volatile double oldestAgeSeconds = Double.NaN;
    private volatile double repeatedFailures = Double.NaN;

    public BlogFeedDispatchMetrics(BlogFeedDispatchMapper mapper, MeterRegistry registry) {
        this.mapper = mapper;
        this.registry = registry;
        Gauge.builder("cityai.blog.feed.dispatch.pending", this, metrics -> metrics.pending)
                .description("Undelivered blog feed fanout tasks").register(registry);
        Gauge.builder("cityai.blog.feed.dispatch.oldest.age", this, metrics -> metrics.oldestAgeSeconds)
                .description("Age in seconds of the oldest undelivered blog feed task").register(registry);
        Gauge.builder("cityai.blog.feed.dispatch.repeated.failures", this, metrics -> metrics.repeatedFailures)
                .description("Undelivered blog feed tasks with repeated delivery failures").register(registry);
    }

    @Scheduled(fixedDelayString = "${blog.feed.dispatch.metrics.refresh-ms:30000}")
    void refreshSnapshot() {
        try {
            BlogFeedDispatchBacklog snapshot = mapper.getBacklogSnapshot(Math.max(1, failureThreshold));
            if (snapshot == null) {
                return;
            }
            pending = Math.max(0, snapshot.getPendingCount());
            oldestAgeSeconds = Math.max(0, snapshot.getOldestPendingAgeSeconds());
            repeatedFailures = Math.max(0, snapshot.getRepeatedFailureCount());
        } catch (Exception e) {
            log.warn("刷新笔记 Feed 投递积压指标失败，继续使用上次快照", e);
        }
    }
}

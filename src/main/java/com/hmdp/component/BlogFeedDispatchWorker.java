package com.hmdp.component;

import com.hmdp.entity.BlogFeedDispatch;
import com.hmdp.mapper.BlogFeedDispatchMapper;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.time.ZoneId;
import java.util.Collections;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.hmdp.utils.RedisConstants.FEED_KEY;

@Slf4j
@Component
@RequiredArgsConstructor
public class BlogFeedDispatchWorker {
    private static final RedisScript<Long> UPSERT_FEED_ENTRY = createUpsertFeedScript();

    private final BlogFeedDispatchMapper mapper;
    private final StringRedisTemplate redis;
    @Value("${blog.feed.max-entries:1000}")
    private int maxFeedEntries = 1000;

    private static RedisScript<Long> createUpsertFeedScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("blog-feed-upsert.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @PostConstruct
    void validateConfiguration() {
        if (maxFeedEntries < 1) {
            throw new IllegalArgumentException("blog.feed.max-entries must be at least 1");
        }
    }

    @Scheduled(fixedDelayString = "${blog.feed.dispatch.delay-ms:1000}")
    public void dispatchPending() {
        String token = UUID.randomUUID().toString();
        try {
            mapper.purgeDeliveredBatch();
            if (mapper.claim(token) < 1) {
                return;
            }
            List<BlogFeedDispatch> tasks = mapper.findClaimed(token);
            if (tasks == null || tasks.isEmpty()) {
                return;
            }
            List<Object> results = redis.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> Object execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> stringOperations =
                            (RedisOperations<String, String>) operations;
                    for (BlogFeedDispatch task : tasks) {
                        if (task.getBlogCreatedAt() == null) {
                            throw new IllegalStateException("Blog creation time is missing from feed dispatch");
                        }
                        double score = task.getBlogCreatedAt().atZone(ZoneId.systemDefault())
                                .toInstant().toEpochMilli();
                        String key = FEED_KEY + task.getFollowerId();
                        stringOperations.execute(UPSERT_FEED_ENTRY, Collections.singletonList(key),
                                Long.toString((long) score), task.getBlogId().toString(),
                                Integer.toString(maxFeedEntries));
                    }
                    return null;
                }
            });
            if (results == null || results.size() != tasks.size()) {
                throw new IllegalStateException("Unexpected Redis pipeline result count");
            }
            List<BlogFeedDispatch> delivered = new ArrayList<>();
            List<BlogFeedDispatch> failed = new ArrayList<>();
            for (int i = 0; i < tasks.size(); i++) {
                BlogFeedDispatch task = tasks.get(i);
                Object result = results.get(i);
                try {
                    if (result instanceof RuntimeException failure) {
                        throw failure;
                    }
                    if (!(result instanceof Long feedSize) || feedSize > maxFeedEntries) {
                        throw new IllegalStateException("Redis feed write returned no result");
                    }
                    delivered.add(task);
                } catch (Exception e) {
                    failed.add(task);
                    log.error("笔记 Feed 投递失败，将持久化重试，blogId={}, followerId={}",
                            task.getBlogId(), task.getFollowerId(), e);
                }
            }
            if (!delivered.isEmpty()) {
                int deleted = mapper.deleteDeliveredBatch(delivered, token);
                if (deleted != delivered.size()) {
                    log.warn("笔记 Feed 批量确认数量与 Redis 成功数不一致，expected={}, deleted={}",
                            delivered.size(), deleted);
                }
            }
            if (!failed.isEmpty()) {
                int deferred = mapper.recordFailures(failed, token);
                if (deferred != failed.size()) {
                    log.warn("笔记 Feed 批量退避数量与 Redis 失败数不一致，expected={}, updated={}",
                            failed.size(), deferred);
                }
            }
        } catch (Exception e) {
            log.error("扫描笔记 Feed 投递任务失败，后续调度将重试", e);
        }
    }
}

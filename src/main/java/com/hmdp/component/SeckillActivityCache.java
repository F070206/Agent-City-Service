package com.hmdp.component;

import com.hmdp.dto.SeckillVoucherLookup;
import com.hmdp.mapper.SeckillVoucherMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_ACTIVITY_KEY;

/** Keeps immutable seckill admission metadata beside the Redis stock used by the Lua hot path. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillActivityCache {
    private static final ZoneId ACTIVITY_TIME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DefaultRedisScript<Long> WRITE_IF_NEWER_SCRIPT = new DefaultRedisScript<>();

    static {
        WRITE_IF_NEWER_SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource(
                "seckill-activity-cache.lua"));
        WRITE_IF_NEWER_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redisTemplate;
    private final SeckillVoucherMapper seckillVoucherMapper;
    private final ConcurrentMap<Long, Long> pendingRefresh = new ConcurrentHashMap<>();

    public Long put(SeckillVoucherLookup activity) {
        if (activity == null || activity.getVoucherId() == null) {
            throw new IllegalArgumentException("Seckill activity and voucher ID are required");
        }
        Long version = activity.getVersion() == null ? 0L : activity.getVersion();
        Long result = redisTemplate.execute(WRITE_IF_NEWER_SCRIPT,
                List.of(SECKILL_ACTIVITY_KEY + activity.getVoucherId()),
                version.toString(), toEpochMillis(activity.getBeginTime()), toEpochMillis(activity.getEndTime()),
                stringValue(activity.getVoucherStatus()), stringValue(activity.getVoucherType()));
        if (result == null) {
            throw new IllegalStateException("秒杀活动缓存版本写入未返回结果，voucherId=" + activity.getVoucherId());
        }
        return result;
    }

    /** Refreshes after the database transaction commits, failing closed if Redis still has old data. */
    public void refreshAfterCommit(SeckillVoucherLookup activity) {
        if (activity == null || activity.getVoucherId() == null) {
            throw new IllegalArgumentException("Seckill activity and voucher ID are required");
        }
        try {
            put(activity);
            clearPendingAtOrBelow(activity.getVoucherId(), versionOf(activity));
        } catch (RuntimeException refreshFailure) {
            pendingRefresh.merge(activity.getVoucherId(), versionOf(activity), Math::max);
            try {
                redisTemplate.delete(SECKILL_ACTIVITY_KEY + activity.getVoucherId());
            } catch (RuntimeException invalidationFailure) {
                refreshFailure.addSuppressed(invalidationFailure);
            }
            log.error("秒杀活动数据库已提交，但 Redis 元数据刷新失败；已尝试失效旧缓存并排队重试，voucherId={}",
                    activity.getVoucherId(), refreshFailure);
        }
    }

    @Scheduled(fixedDelayString = "${seckill.activity-cache.retry-ms:5000}")
    public void retryPendingRefreshes() {
        pendingRefresh.forEach((voucherId, pendingVersion) -> {
            try {
                SeckillVoucherLookup latest = seckillVoucherMapper.findForSeckill(voucherId);
                if (latest == null) {
                    redisTemplate.delete(SECKILL_ACTIVITY_KEY + voucherId);
                    pendingRefresh.remove(voucherId, pendingVersion);
                    return;
                }
                put(latest);
                clearPendingAtOrBelow(voucherId, versionOf(latest));
            } catch (RuntimeException refreshFailure) {
                log.warn("重试秒杀活动 Redis 元数据刷新失败，voucherId={}", voucherId, refreshFailure);
            }
        });
    }

    private void clearPendingAtOrBelow(Long voucherId, Long writtenVersion) {
        pendingRefresh.computeIfPresent(voucherId,
                (ignored, pendingVersion) -> pendingVersion <= writtenVersion ? null : pendingVersion);
    }

    private Long versionOf(SeckillVoucherLookup activity) {
        return activity.getVersion() == null ? 0L : activity.getVersion();
    }

    private String toEpochMillis(LocalDateTime value) {
        return value == null ? "" : Long.toString(value.atZone(ACTIVITY_TIME_ZONE).toInstant().toEpochMilli());
    }

    private String stringValue(Integer value) {
        return value == null ? "" : value.toString();
    }
}

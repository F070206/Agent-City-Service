package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private final ShopCacheMetrics shopCacheMetrics;
    private final Executor cacheRebuildExecutor;
    private final ScheduledExecutorService lockLeaseScheduler;

    private static final class DirectConstructionExecutor {
        private static final ThreadPoolExecutor INSTANCE = createDefaultExecutor();

        private static ThreadPoolExecutor createDefaultExecutor() {
            ThreadPoolExecutor executor = new ThreadPoolExecutor(10, 10, 60, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1000), runnable -> {
                        Thread thread = new Thread(runnable, "shop-cache-rebuild");
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            executor.allowCoreThreadTimeOut(true);
            return executor;
        }
    }

    private static final ScheduledExecutorService CACHE_LOCK_LEASE_SCHEDULER = Executors.newScheduledThreadPool(2,
            runnable -> {
                Thread thread = new Thread(runnable, "shop-cache-lock-lease");
                thread.setDaemon(true);
                return thread;
            });
    private static final DefaultRedisScript<Long> REPLACE_IF_UNCHANGED = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end "
                    + "redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3]) return 1", Long.class);
    private static final DefaultRedisScript<Long> UNLOCK_IF_OWNED = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end return 0", Long.class);
    private static final DefaultRedisScript<Long> RENEW_IF_OWNED = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('expire', KEYS[1], ARGV[2]) end return 0", Long.class);

    @Autowired
    public CacheClient(StringRedisTemplate stringRedisTemplate, ShopCacheMetrics shopCacheMetrics,
            ShopCacheRebuildExecutor cacheRebuildExecutor) {
        this(stringRedisTemplate, shopCacheMetrics, cacheRebuildExecutor.executor());
    }

    CacheClient(StringRedisTemplate stringRedisTemplate, ShopCacheMetrics shopCacheMetrics) {
        this(stringRedisTemplate, shopCacheMetrics, DirectConstructionExecutor.INSTANCE);
    }

    CacheClient(StringRedisTemplate stringRedisTemplate, ShopCacheMetrics shopCacheMetrics,
            Executor cacheRebuildExecutor) {
        this(stringRedisTemplate, shopCacheMetrics, cacheRebuildExecutor, CACHE_LOCK_LEASE_SCHEDULER);
    }

    CacheClient(StringRedisTemplate stringRedisTemplate, ShopCacheMetrics shopCacheMetrics,
            Executor cacheRebuildExecutor, ScheduledExecutorService lockLeaseScheduler) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.shopCacheMetrics = shopCacheMetrics;
        this.cacheRebuildExecutor = cacheRebuildExecutor;
        this.lockLeaseScheduler = lockLeaseScheduler;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, logicalValue(value, time, unit),
                logicalPhysicalTtl(time, unit), TimeUnit.SECONDS);
    }

    private String logicalValue(Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireAtEpochMillis(System.currentTimeMillis() + unit.toMillis(time));
        // Keep the previous wall-clock field while old instances may still read this shared cache.
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        return JSONUtil.toJsonStr(redisData);
    }

    private long logicalPhysicalTtl(Long time, TimeUnit unit) {
        return Math.max(1, unit.toSeconds(time) * 2);
    }

    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if (json != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 5.不存在，返回错误
        if (r == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 6.存在，写入redis
        this.set(key, r, time, unit);
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        return queryWithLogicalExpire(keyPrefix, id, type, dbFallback, ignored -> true, time, unit);
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
            Predicate<ID> cacheMissGuard, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null) {
                if (!cacheMissGuard.test(id)) {
                    return null;
                }
                shopCacheMetrics.recordMiss();
                return loadLogicalWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            R result = readLogicalValue(keyPrefix, id, type, dbFallback, time, unit, json);
            if (StrUtil.isBlank(json)) {
                shopCacheMetrics.recordEmptyHit();
            } else {
                shopCacheMetrics.recordValueHit();
            }
            return result;
        } catch (RuntimeException e) {
            shopCacheMetrics.recordError();
            throw e;
        }
    }

    private <R, ID> R readLogicalValue(String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long time, TimeUnit unit, String json) {
        String key = keyPrefix + id;
        if (StrUtil.isBlank(json)) {
            return null;
        }
        JSONObject cached = JSONUtil.parseObj(json);
        // 切换缓存格式期间，旧的普通值继续使用原有物理 TTL。
        if (!cached.containsKey("expireTime")) {
            if (cached.containsKey("expireAtEpochMillis")) {
                throw new IllegalStateException("Logical cache has no compatible expiration field: " + key);
            }
            return JSONUtil.toBean(json, type);
        }
        RedisData redisData = JSONUtil.toBean(cached, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        Long expireAtEpochMillis = redisData.getExpireAtEpochMillis();
        boolean notExpired;
        if (expireAtEpochMillis != null) {
            notExpired = expireAtEpochMillis > System.currentTimeMillis();
        } else {
            // Older entries used a local wall-clock timestamp; read them until their physical TTL expires.
            LocalDateTime expireTime = redisData.getExpireTime();
            notExpired = expireTime != null && expireTime.isAfter(LocalDateTime.now());
        }
        if (notExpired) {
            return r;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        String token;
        try {
            token = tryTokenLock(lockKey);
        } catch (RuntimeException e) {
            shopCacheMetrics.recordError();
            log.warn("获取缓存重建锁失败，继续返回逻辑过期数据: {}", key, e);
            return r;
        }
        if (token != null) {
            ScheduledFuture<?> leaseRenewal = startLockLeaseRenewal(lockKey, token);
            try {
                cacheRebuildExecutor.execute(() -> {
                    try {
                        R newR = dbFallback.apply(id);
                        String replacement = newR == null ? "" : logicalValue(newR, time, unit);
                        long ttl = newR == null ? TimeUnit.MINUTES.toSeconds(CACHE_NULL_TTL)
                                : logicalPhysicalTtl(time, unit);
                        stringRedisTemplate.execute(REPLACE_IF_UNCHANGED, Collections.singletonList(key),
                                json, replacement, Long.toString(ttl));
                    } catch (Exception e) {
                        log.error("缓存异步重建失败: {}", key, e);
                    } finally {
                        leaseRenewal.cancel(false);
                        unlockIfOwned(lockKey, token);
                    }
                });
            } catch (RejectedExecutionException e) {
                leaseRenewal.cancel(false);
                unlockIfOwned(lockKey, token);
                shopCacheMetrics.recordRefreshRejection();
                log.warn("缓存重建队列已满，继续返回旧缓存: {}", key);
            }
        }
        return r;
    }

    private <R, ID> R loadLogicalWithMutex(String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String lockKey = LOCK_SHOP_KEY + id;
        for (int attempt = 0; attempt < 200; attempt++) {
            String token = tryTokenLock(lockKey);
            if (token != null) {
                ScheduledFuture<?> leaseRenewal = startLockLeaseRenewal(lockKey, token);
                try {
                    String existing = stringRedisTemplate.opsForValue().get(key);
                    if (existing != null) {
                        return readLogicalValue(keyPrefix, id, type, dbFallback, time, unit, existing);
                    }
                    R loaded = dbFallback.apply(id);
                    Boolean inserted;
                    if (loaded == null) {
                        inserted = stringRedisTemplate.opsForValue().setIfAbsent(
                                key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    } else {
                        inserted = stringRedisTemplate.opsForValue().setIfAbsent(key, logicalValue(loaded, time, unit),
                                logicalPhysicalTtl(time, unit), TimeUnit.SECONDS);
                    }
                    if (!Boolean.TRUE.equals(inserted)) {
                        String newer = stringRedisTemplate.opsForValue().get(key);
                        if (newer != null) {
                            return readLogicalValue(keyPrefix, id, type, dbFallback, time, unit, newer);
                        }
                    }
                    return loaded;
                } finally {
                    leaseRenewal.cancel(false);
                    unlockIfOwned(lockKey, token);
                }
            }
            String existing = stringRedisTemplate.opsForValue().get(key);
            if (existing != null) {
                return readLogicalValue(keyPrefix, id, type, dbFallback, time, unit, existing);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待缓存重建被中断", e);
            }
        }
        throw new IllegalStateException("等待缓存重建超时: " + key);
    }

    private String tryTokenLock(String key) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(key, token, LOCK_SHOP_TTL,
                TimeUnit.SECONDS);
        return BooleanUtil.isTrue(acquired) ? token : null;
    }

    private ScheduledFuture<?> startLockLeaseRenewal(String key, String token) {
        long leaseSeconds = Math.max(1, LOCK_SHOP_TTL);
        long renewalSeconds = Math.max(1, leaseSeconds / 3);
        return lockLeaseScheduler.scheduleAtFixedRate(() -> {
            try {
                Long renewed = stringRedisTemplate.execute(RENEW_IF_OWNED, Collections.singletonList(key),
                        token, Long.toString(leaseSeconds));
                if (!Long.valueOf(1).equals(renewed)) {
                    log.warn("缓存锁租约不再属于当前任务，停止续租: {}", key);
                }
            } catch (RuntimeException e) {
                log.error("缓存锁续租失败: {}", key, e);
            }
        }, renewalSeconds, renewalSeconds, TimeUnit.SECONDS);
    }

    private void unlockIfOwned(String key, String token) {
        stringRedisTemplate.execute(UNLOCK_IF_OWNED, Collections.singletonList(key), token);
    }

    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String lockKey = LOCK_SHOP_KEY + id;
        for (int attempt = 0; attempt < 200; attempt++) {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null) {
                return StrUtil.isBlank(cached) ? null : JSONUtil.toBean(cached, type);
            }

            String token = tryTokenLock(lockKey);
            if (token != null) {
                ScheduledFuture<?> leaseRenewal = startLockLeaseRenewal(lockKey, token);
                try {
                    // Another request may have filled the cache between our first read and lock acquisition.
                    cached = stringRedisTemplate.opsForValue().get(key);
                    if (cached != null) {
                        return StrUtil.isBlank(cached) ? null : JSONUtil.toBean(cached, type);
                    }
                    R loaded = dbFallback.apply(id);
                    if (loaded == null) {
                        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    } else {
                        this.set(key, loaded, time, unit);
                    }
                    return loaded;
                } finally {
                    leaseRenewal.cancel(false);
                    unlockIfOwned(lockKey, token);
                }
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待缓存重建被中断: " + key, e);
            }
        }
        throw new IllegalStateException("等待缓存重建超时: " + key);
    }
}

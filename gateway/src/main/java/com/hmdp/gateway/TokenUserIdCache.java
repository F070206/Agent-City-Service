package com.hmdp.gateway;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/** Short-lived, bounded cache for resolving valid login tokens to their user ID. */
final class TokenUserIdCache {
    private static final int DEFAULT_MAX_ENTRIES = 10_000;
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(2);

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Object insertionLock = new Object();
    private final int maxEntries;
    private final long ttlNanos;
    private final LongSupplier ticker;
    private long lastCleanupNanos;

    TokenUserIdCache() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL, System::nanoTime);
    }

    TokenUserIdCache(int maxEntries, Duration ttl, LongSupplier ticker) {
        if (maxEntries < 1 || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Token owner cache requires a positive capacity and TTL");
        }
        this.maxEntries = maxEntries;
        this.ttlNanos = ttl.toNanos();
        this.ticker = ticker;
        this.lastCleanupNanos = ticker.getAsLong();
    }

    String get(String token) {
        Entry entry = entries.get(token);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(ticker.getAsLong())) {
            entries.remove(token, entry);
            return null;
        }
        return entry.userId();
    }

    void put(String token, String userId) {
        long now = ticker.getAsLong();
        synchronized (insertionLock) {
            Entry existing = entries.get(token);
            if (existing != null && !existing.isExpired(now)) {
                return;
            }
            if (entries.size() >= maxEntries && now - lastCleanupNanos >= ttlNanos) {
                entries.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
                lastCleanupNanos = now;
            }
            if (entries.size() < maxEntries) {
                entries.put(token, new Entry(userId, now + ttlNanos));
            }
        }
    }

    private record Entry(String userId, long expiresAtNanos) {
        boolean isExpired(long now) {
            return now - expiresAtNanos >= 0;
        }
    }
}

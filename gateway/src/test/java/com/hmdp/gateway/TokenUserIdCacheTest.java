package com.hmdp.gateway;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TokenUserIdCacheTest {
    @Test
    void expiresMappingsAtConfiguredTtl() {
        AtomicLong ticker = new AtomicLong(10);
        TokenUserIdCache cache = new TokenUserIdCache(2, Duration.ofSeconds(2), ticker::get);
        cache.put("token", "7");

        assertEquals("7", cache.get("token"));
        ticker.addAndGet(Duration.ofSeconds(2).toNanos());
        assertNull(cache.get("token"));
    }

    @Test
    void staysBoundedAndDoesNotCacheNewMappingsWhenFull() {
        AtomicLong ticker = new AtomicLong(10);
        TokenUserIdCache cache = new TokenUserIdCache(1, Duration.ofSeconds(2), ticker::get);
        cache.put("first", "1");
        cache.put("second", "2");

        assertEquals("1", cache.get("first"));
        assertNull(cache.get("second"));

        ticker.addAndGet(Duration.ofSeconds(2).toNanos());
        cache.put("second", "2");
        assertNull(cache.get("first"));
        assertEquals("2", cache.get("second"));
    }
}

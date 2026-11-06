package com.hmdp.ai.mcp;

import java.time.Duration;

final class McpTimeouts {
    static final int MIN_MILLIS = 500;
    static final int MAX_MILLIS = 30_000;

    private McpTimeouts() {
    }

    static Duration boundedDuration(int configuredMillis) {
        return Duration.ofMillis(Math.min(Math.max(configuredMillis, MIN_MILLIS), MAX_MILLIS));
    }
}

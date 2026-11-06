package com.hmdp.ai.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpTimeoutsTest {
    @Test
    void boundsMcpTimeoutForBothTransports() {
        assertEquals(500, McpTimeouts.boundedDuration(-1).toMillis());
        assertEquals(2_500, McpTimeouts.boundedDuration(2_500).toMillis());
        assertEquals(30_000, McpTimeouts.boundedDuration(Integer.MAX_VALUE).toMillis());
    }
}

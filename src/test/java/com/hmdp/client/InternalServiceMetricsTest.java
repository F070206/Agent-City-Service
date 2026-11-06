package com.hmdp.client;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InternalServiceMetricsTest {
    @Test
    void recordsSuccessAndFailureWithBoundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InternalServiceMetrics metrics = new InternalServiceMetrics(registry);

        assertEquals("ok", metrics.record("content", "shop", () -> "ok"));
        assertThrows(IllegalStateException.class,
                () -> metrics.record("content", "shop", () -> { throw new IllegalStateException("down"); }));

        assertEquals(1.0, registry.get("cityai.internal.client.requests")
                .tag("service", "content").tag("operation", "shop").tag("outcome", "success").timer().count());
        assertEquals(1.0, registry.get("cityai.internal.client.requests")
                .tag("service", "content").tag("operation", "shop").tag("outcome", "failure").timer().count());
    }
}

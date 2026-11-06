package com.hmdp.client;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RemoteContentCatalogCircuitTest {
    @AfterEach
    void clearRules() {
        DegradeRuleManager.loadRules(List.of());
    }

    @Test
    void contentFailuresOpenCircuitAndShortCircuitLaterFeignCalls() {
        DegradeRuleManager.loadRules(List.of(new DegradeRule("ai:content")
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.5)
                .setMinRequestAmount(1)
                .setStatIntervalMs(1000)
                .setTimeWindow(10)));
        ContentFeignClient client = mock(ContentFeignClient.class);
        RemoteContentCatalog catalog = new RemoteContentCatalog(client,
                new InternalServiceMetrics(new SimpleMeterRegistry()));
        when(client.shop(1L)).thenThrow(new IllegalStateException("content unavailable"));

        assertThrows(IllegalStateException.class, () -> catalog.shop(1L));
        IllegalStateException blocked = assertThrows(IllegalStateException.class, () -> catalog.shop(2L));

        assertTrue(blocked.getMessage().contains("熔断"));
        verify(client).shop(1L);
        verifyNoMoreInteractions(client);
    }

    @Test
    void slowContentCallsOpenCircuitAndAvoidRetryingUnavailableService() {
        DegradeRuleManager.loadRules(List.of(new DegradeRule("ai:content")
                .setGrade(RuleConstant.DEGRADE_GRADE_RT)
                .setCount(1)
                .setSlowRatioThreshold(0.5)
                .setMinRequestAmount(1)
                .setStatIntervalMs(1000)
                .setTimeWindow(10)));
        ContentFeignClient client = mock(ContentFeignClient.class);
        RemoteContentCatalog catalog = new RemoteContentCatalog(client,
                new InternalServiceMetrics(new SimpleMeterRegistry()));
        AtomicInteger calls = new AtomicInteger();
        when(client.shop(1L)).thenAnswer(invocation -> {
            calls.incrementAndGet();
            Thread.sleep(10);
            return null;
        });

        catalog.shop(1L);
        IllegalStateException blocked = assertThrows(IllegalStateException.class, () -> catalog.shop(2L));

        assertTrue(blocked.getMessage().contains("熔断"));
        assertTrue(calls.get() == 1);
    }
}

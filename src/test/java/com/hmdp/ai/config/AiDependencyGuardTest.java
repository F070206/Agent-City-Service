package com.hmdp.ai.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

class AiDependencyGuardTest {
    @AfterEach
    void clearRules() {
        DegradeRuleManager.loadRules(List.of());
    }

    @Test
    void errorRatioOpensCircuitAndAllowsRecoveryProbe() throws Exception {
        String resource = "test:ai:dependency:recovery";
        DegradeRuleManager.loadRules(List.of(new DegradeRule(resource)
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.5)
                .setMinRequestAmount(1)
                .setStatIntervalMs(1000)
                .setTimeWindow(1)));

        assertThrows(IllegalStateException.class, () -> AiDependencyGuard.call(resource,
                () -> { throw new IllegalStateException("remote failure"); }));
        IllegalStateException blocked = assertThrows(IllegalStateException.class,
                () -> AiDependencyGuard.call(resource, () -> "ok"));
        assertTrue(blocked.getMessage().contains("熔断"));

        Thread.sleep(1200);
        assertEquals("recovered", AiDependencyGuard.call(resource, () -> "recovered"));
    }

    @Test
    void slowRequestRatioOpensCircuit() throws Exception {
        String resource = "test:ai:dependency:slow";
        DegradeRuleManager.loadRules(List.of(new DegradeRule(resource)
                .setGrade(RuleConstant.DEGRADE_GRADE_RT)
                .setCount(1)
                .setSlowRatioThreshold(0.5)
                .setMinRequestAmount(1)
                .setStatIntervalMs(1000)
                .setTimeWindow(1)));

        assertEquals("slow", AiDependencyGuard.call(resource, () -> {
            Thread.sleep(10);
            return "slow";
        }));
        IllegalStateException blocked = assertThrows(IllegalStateException.class,
                () -> AiDependencyGuard.call(resource, () -> "fast"));
        assertTrue(blocked.getMessage().contains("熔断"));
    }

    @Test
    void slowStreamDeliveryDoesNotCountAsSlowModelCall() throws Exception {
        String resource = "test:ai:stream:slow-delivery";
        String readResource = resource + ":read";
        DegradeRuleManager.loadRules(List.of(errorRule(resource), slowRule(readResource, 20)));

        AiDependencyGuard.consume(resource, readResource, () -> List.of("chunk"),
                value -> Thread.sleep(60));

        assertEquals("model healthy", AiDependencyGuard.call(resource, () -> "model healthy"));
    }

    @Test
    void slowStreamReadStillOpensReadCircuit() {
        String resource = "test:ai:stream:slow-read";
        String readResource = resource + ":read";
        DegradeRuleManager.loadRules(List.of(errorRule(resource), slowRule(readResource, 20)));
        Iterable<String> slowSource = () -> new Iterator<>() {
            private boolean emitted;

            @Override
            public boolean hasNext() {
                try {
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return !emitted;
            }

            @Override
            public String next() {
                emitted = true;
                return "chunk";
            }
        };

        assertThrows(IllegalStateException.class,
                () -> AiDependencyGuard.consume(resource, readResource, () -> slowSource, value -> { }));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> AiDependencyGuard.consume(resource, readResource, () -> List.of("retry"), value -> { }))
                .getMessage().contains("熔断"));
    }

    private DegradeRule slowRule(String resource, int thresholdMillis) {
        return new DegradeRule(resource)
                .setGrade(RuleConstant.DEGRADE_GRADE_RT)
                .setCount(thresholdMillis)
                .setSlowRatioThreshold(0.5)
                .setMinRequestAmount(1)
                .setStatIntervalMs(1000)
                .setTimeWindow(10);
    }

    @Test
    void streamReadFailureOpensCircuitButDeliveryFailureDoesNot() throws Exception {
        String readResource = "test:ai:stream:read";
        String deliveryResource = "test:ai:stream:delivery";
        DegradeRuleManager.loadRules(List.of(errorRule(readResource), errorRule(deliveryResource)));

        assertThrows(IllegalStateException.class, () -> AiDependencyGuard.consume(readResource,
                () -> () -> new Iterator<String>() {
                    @Override
                    public boolean hasNext() {
                        throw new IllegalStateException("model stream failed");
                    }

                    @Override
                    public String next() {
                        return "unused";
                    }
                }, value -> { }));
        assertTrue(assertThrows(IllegalStateException.class, () -> AiDependencyGuard.consume(readResource,
                () -> List.of("ok"), value -> { })).getMessage().contains("熔断"));

        assertThrows(IllegalStateException.class, () -> AiDependencyGuard.consume(deliveryResource,
                () -> List.of("ok"), value -> { throw new IllegalStateException("SSE client gone"); }));
        AiDependencyGuard.consume(deliveryResource, () -> List.of("ok"), value -> { });
    }

    private DegradeRule errorRule(String resource) {
        return new DegradeRule(resource)
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.5)
                .setMinRequestAmount(1)
                .setStatIntervalMs(1000)
                .setTimeWindow(1);
    }
}

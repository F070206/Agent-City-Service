package com.hmdp.ai.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiDependencyCircuitRulesTest {
    @AfterEach
    void clearRules() {
        DegradeRuleManager.loadRules(List.of());
    }

    @Test
    void loadsConfiguredRulesForEveryExternalAiDependency() {
        AiAgentProperties properties = new AiAgentProperties();
        AiAgentProperties.Circuit circuit = properties.getCircuit();
        circuit.setWindowSeconds(11);
        circuit.setRecoverySeconds(7);
        circuit.setMinRequests(3);
        circuit.setErrorRatio(0.4);
        circuit.setSlowRatio(0.6);
        circuit.setModelSlowMillis(125);
        circuit.setStreamSlowMillis(250);
        circuit.setToolSlowMillis(375);

        new AiDependencyCircuitRules(properties).loadRules();

        List<DegradeRule> rules = DegradeRuleManager.getRules();
        assertEquals(14, rules.size());
        assertEquals(Set.of("ai:model:think", "ai:model:stream", "ai:model:stream:read",
                        "ai:mcp:route", "ai:mcp:weather", "ai:content", "ai:rag:search", "ai:rag:index"),
                rules.stream().map(DegradeRule::getResource).collect(Collectors.toSet()));
        assertTrue(rules.stream().allMatch(rule -> rule.getStatIntervalMs() == 11_000
                && rule.getTimeWindow() == 7 && rule.getMinRequestAmount() == 3));

        for (String resource : Set.of("ai:model:think", "ai:mcp:route", "ai:mcp:weather", "ai:content",
                "ai:rag:search", "ai:rag:index")) {
            DegradeRule slowRule = ruleFor(rules, resource, RuleConstant.DEGRADE_GRADE_RT);
            DegradeRule errorRule = ruleFor(rules, resource, RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
            int expectedSlowMillis = resource.equals("ai:model:think") ? 125 : 375;
            assertEquals(expectedSlowMillis, slowRule.getCount());
            assertEquals(0.6, slowRule.getSlowRatioThreshold());
            assertEquals(0.4, errorRule.getCount());
        }

        DegradeRule streamErrorRule = ruleFor(rules, "ai:model:stream", RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        DegradeRule streamReadSlowRule = ruleFor(rules, "ai:model:stream:read", RuleConstant.DEGRADE_GRADE_RT);
        assertEquals(0.4, streamErrorRule.getCount());
        assertEquals(250, streamReadSlowRule.getCount());
    }

    private DegradeRule ruleFor(List<DegradeRule> rules, String resource, int grade) {
        return rules.stream()
                .filter(rule -> resource.equals(rule.getResource()) && rule.getGrade() == grade)
                .findFirst()
                .orElseThrow();
    }
}

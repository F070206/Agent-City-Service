package com.hmdp.ai.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class AiDependencyCircuitRules {
    private final AiAgentProperties properties;

    @PostConstruct
    public void loadRules() {
        AiAgentProperties.Circuit settings = properties.getCircuit();
        if (!settings.isEnabled()) {
            return;
        }
        if (settings.getMinRequests() < 1 || settings.getWindowSeconds() < 1
                || settings.getWindowSeconds() > 86400
                || settings.getRecoverySeconds() < 1 || settings.getModelSlowMillis() < 1
                || settings.getStreamSlowMillis() < 1
                || settings.getToolSlowMillis() < 1 || !ratioValid(settings.getErrorRatio())
                || !ratioValid(settings.getSlowRatio())) {
            throw new IllegalArgumentException("Invalid AI circuit breaker settings");
        }

        List<DegradeRule> rules = new ArrayList<>();
        addRules(rules, "ai:model:think", settings.getModelSlowMillis(), settings);
        addExceptionRule(rules, "ai:model:stream", settings);
        addSlowRule(rules, "ai:model:stream:read", settings.getStreamSlowMillis(), settings);
        addRules(rules, "ai:mcp:route", settings.getToolSlowMillis(), settings);
        addRules(rules, "ai:mcp:weather", settings.getToolSlowMillis(), settings);
        addRules(rules, "ai:content", settings.getToolSlowMillis(), settings);
        addRules(rules, "ai:rag:search", settings.getToolSlowMillis(), settings);
        addRules(rules, "ai:rag:index", settings.getToolSlowMillis(), settings);
        DegradeRuleManager.loadRules(rules);
    }

    private boolean ratioValid(double value) {
        return value > 0 && value <= 1;
    }

    private void addRules(List<DegradeRule> rules, String resource, int slowMillis,
            AiAgentProperties.Circuit settings) {
        addSlowRule(rules, resource, slowMillis, settings);
        addExceptionRule(rules, resource, settings);
    }

    private void addSlowRule(List<DegradeRule> rules, String resource, int slowMillis,
            AiAgentProperties.Circuit settings) {
        rules.add(baseRule(resource, settings)
                .setGrade(RuleConstant.DEGRADE_GRADE_RT)
                .setCount(slowMillis)
                .setSlowRatioThreshold(settings.getSlowRatio()));
    }

    private void addExceptionRule(List<DegradeRule> rules, String resource,
            AiAgentProperties.Circuit settings) {
        rules.add(baseRule(resource, settings)
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(settings.getErrorRatio()));
    }

    private DegradeRule baseRule(String resource, AiAgentProperties.Circuit settings) {
        return new DegradeRule(resource)
                .setMinRequestAmount(settings.getMinRequests())
                .setStatIntervalMs(settings.getWindowSeconds() * 1000)
                .setTimeWindow(settings.getRecoverySeconds());
    }
}

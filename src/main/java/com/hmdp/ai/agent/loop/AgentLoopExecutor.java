package com.hmdp.ai.agent.loop;

import com.hmdp.ai.agent.scene.AiAgentScene;
import com.hmdp.ai.service.impl.AiTaskLease;
import com.hmdp.component.AgentLoopObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
@RequiredArgsConstructor
public class AgentLoopExecutor {

    private final ManualToolAgentLoopExecutor manualToolAgentLoopExecutor;

    public AgentLoopResult execute(AgentLoopRequest request) {
        return execute(request, AgentLoopObserver.noop());
    }

    public AgentLoopResult execute(AgentLoopRequest request, AgentLoopObserver observer) {
        return execute(request, observer, null);
    }

    public AgentLoopResult execute(AgentLoopRequest request, AgentLoopObserver observer, AiTaskLease lease) {
        AgentLoopResult result = new AgentLoopResult();
        result.setScene(AiAgentScene.MULTI_AGENT.name());
        result.setToolLogs(new ArrayList<>());
        result.setAgentLoopExecution(manualToolAgentLoopExecutor.execute(
                request, result.getToolLogs(), observer, lease));
        return result;
    }
}

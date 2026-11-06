package com.hmdp.ai.service.impl;

import com.hmdp.ai.entity.AiMessage;
import com.hmdp.ai.memory.ConversationMemoryService;
import com.hmdp.ai.mapper.AiTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiMessageFailureRecorder {
    private final ConversationMemoryService conversationMemoryService;
    private final AiTaskMapper tasks;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiMessage recordBusy(Long messageId, String claimToken, Long conversationId, String reply) {
        AiMessage answer = conversationMemoryService.appendAssistantMessage(conversationId, reply);
        if (tasks.complete(messageId, claimToken) != 1) {
            throw new IllegalStateException("AI 拒绝任务完成状态更新失败，消息ID=" + messageId);
        }
        return answer;
    }
}

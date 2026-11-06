package com.hmdp.ai.service.impl;

import com.hmdp.ai.entity.AiMessage;
import com.hmdp.ai.mapper.AiTaskMapper;
import com.hmdp.ai.memory.ConversationMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiTaskCompletionService {
    private final ConversationMemoryService memory;
    private final AiTaskMapper tasks;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiMessage complete(Long messageId, String token, Long conversationId, String reply,
                              String scene, String latestPrompt) {
        AiMessage answer = memory.appendAssistantMessage(conversationId, reply);
        if (tasks.complete(messageId, token) != 1) {
            throw new IllegalStateException("AI 任务完成状态更新失败，消息ID=" + messageId);
        }
        memory.updateConversation(conversationId, scene, latestPrompt);
        return answer;
    }

}

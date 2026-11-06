package com.hmdp.ai.service.impl;

import com.hmdp.ai.entity.AiTask;
import com.hmdp.ai.mapper.AiTaskMapper;
import com.hmdp.component.ChatMessageCreatedEventListener;
import com.hmdp.event.ChatMessageCreatedEvent;
import lombok.RequiredArgsConstructor;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiTaskRecoveryWorker {
    private final AiTaskMapper tasks;
    private final ChatMessageCreatedEventListener listener;

    @Scheduled(fixedDelayString = "${ai.agent.task-recovery.delay-ms:30000}")
    public void recover() {
        for (AiTask task : tasks.findRecoverable()) {
            String claimToken = UUID.randomUUID().toString();
            if (tasks.claim(task.getMessageId(), claimToken) == 1) {
                listener.submitRecovered(new ChatMessageCreatedEvent(task.getUserId(),
                        task.getConversationId(), task.getMessageId(), task.getContent(),
                        task.getX(), task.getY()), claimToken);
            }
        }
    }
}

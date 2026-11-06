package com.hmdp.ai.service.impl;

import com.hmdp.ai.entity.AiTask;
import com.hmdp.ai.mapper.AiTaskMapper;
import com.hmdp.component.ChatMessageCreatedEventListener;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AiTaskRecoveryWorkerTest {
    @Test
    void submitsOnlyTasksSuccessfullyClaimedBeforeQueueing() {
        AiTaskMapper mapper = mock(AiTaskMapper.class);
        ChatMessageCreatedEventListener listener = mock(ChatMessageCreatedEventListener.class);
        AiTask first = task(1L);
        AiTask second = task(2L);
        when(mapper.findRecoverable()).thenReturn(List.of(first, second));
        when(mapper.claim(eq(1L), anyString())).thenReturn(1);
        when(mapper.claim(eq(2L), anyString())).thenReturn(0);

        new AiTaskRecoveryWorker(mapper, listener).recover();

        verify(listener, times(1)).submitRecovered(any(), anyString());
        verify(mapper).claim(eq(1L), anyString());
        verify(mapper).claim(eq(2L), anyString());
    }

    private AiTask task(Long messageId) {
        AiTask task = new AiTask();
        task.setMessageId(messageId);
        task.setConversationId(10L);
        task.setUserId(20L);
        task.setContent("请求");
        return task;
    }
}

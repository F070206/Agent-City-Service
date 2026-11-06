package com.hmdp.ai.service.impl;

import com.hmdp.ai.entity.AiMessage;
import com.hmdp.ai.mapper.AiTaskMapper;
import com.hmdp.ai.memory.ConversationMemoryService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiTaskCompletionServiceTest {
    @Test
    void completionRequiresClaimedTask() {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        AiTaskMapper tasks = mock(AiTaskMapper.class);
        when(memory.appendAssistantMessage(2L, "回答")).thenReturn(new AiMessage().setId(4L));
        AiTaskCompletionService service = new AiTaskCompletionService(memory, tasks);
        assertThrows(IllegalStateException.class,
                () -> service.complete(3L, "token", 2L, "回答", "shop", "附近"));
        verify(memory, never()).updateConversation(anyLong(), any(), any());
        when(tasks.complete(3L, "token")).thenReturn(1);
        assertEquals(4L, service.complete(3L, "token", 2L, "回答", "shop", "附近").getId());
        verify(memory).updateConversation(2L, "shop", "附近");
    }
}

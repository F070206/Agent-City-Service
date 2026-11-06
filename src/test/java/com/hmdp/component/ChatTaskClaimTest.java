package com.hmdp.component;

import com.hmdp.ai.agent.loop.AgentLoopExecutor;
import com.hmdp.ai.agent.prompt.AgentLoopPromptProvider;
import com.hmdp.ai.config.AiAgentProperties;
import com.hmdp.ai.entity.AiMessage;
import com.hmdp.ai.entity.AiTask;
import com.hmdp.ai.mapper.AiTaskMapper;
import com.hmdp.ai.memory.ConversationMemoryService;
import com.hmdp.ai.service.impl.AiTaskCompletionService;
import com.hmdp.ai.service.impl.AiTaskLeaseKeeper;
import com.hmdp.ai.service.impl.AiTaskLease;
import com.hmdp.dto.ChatStreamEvent;
import com.hmdp.event.ChatMessageCreatedEvent;
import com.hmdp.service.ChatSseService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.Executor;

import static org.mockito.Mockito.*;

class ChatTaskClaimTest {
    @Test
    void completionSubmitsNextPendingMessageImmediately() {
        AiTaskMapper tasks = mock(AiTaskMapper.class);
        when(tasks.claim(eq(2L), anyString())).thenReturn(1);
        when(tasks.claim(eq(3L), anyString())).thenReturn(0);
        AiTask next = new AiTask();
        next.setMessageId(3L);
        next.setConversationId(1L);
        next.setUserId(7L);
        next.setContent("下一条消息");
        when(tasks.findNextPending(1L)).thenReturn(next);
        AiTaskLeaseKeeper leases = mock(AiTaskLeaseKeeper.class);
        doReturn(mock(AiTaskLease.class)).when(leases).start(eq(2L), anyString());
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.loadRecentMessagesBefore(anyLong(), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("stop before model call"));
        AiTaskCompletionService completion = mock(AiTaskCompletionService.class);
        when(completion.complete(eq(2L), anyString(), eq(1L), anyString(), any(), anyString()))
                .thenReturn(new AiMessage().setId(4L).setConversationId(1L).setContent("兜底回复"));
        ChatMessageCreatedEventListener listener = new ChatMessageCreatedEventListener(
                mock(AgentLoopExecutor.class), memory, new AgentLoopPromptProvider(),
                new AiAgentProperties(), mock(ChatSseService.class),
                new ConversationExecutionCoordinator(), mock(ObjectProvider.class));
        ReflectionTestUtils.setField(listener, "aiAgentTaskExecutor", (Executor) Runnable::run);
        ReflectionTestUtils.setField(listener, "aiTaskMapper", tasks);
        ReflectionTestUtils.setField(listener, "completionService", completion);
        ReflectionTestUtils.setField(listener, "leaseKeeper", leases);

        listener.submitRecovered(new ChatMessageCreatedEvent(7L, 1L, 2L, "当前消息", null, null));

        verify(tasks).findNextPending(1L);
        verify(tasks).claim(eq(3L), anyString());
    }

    @Test
    void claimedTaskStopsHeartbeatAfterFailureReplyIsSaved() {
        AiTaskMapper tasks = mock(AiTaskMapper.class);
        when(tasks.claim(eq(2L), anyString())).thenReturn(1);
        AiTaskLeaseKeeper leases = mock(AiTaskLeaseKeeper.class);
        AiTaskLease heartbeat = mock(AiTaskLease.class);
        doReturn(heartbeat).when(leases).start(eq(2L), anyString());
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.loadRecentMessagesBefore(anyLong(), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("model input unavailable"));
        AiTaskCompletionService completion = mock(AiTaskCompletionService.class);
        when(completion.complete(eq(2L), anyString(), eq(1L), anyString(), any(), anyString()))
                .thenReturn(new AiMessage().setId(3L).setConversationId(1L).setContent("请求失败"));
        ChatMessageCreatedEventListener listener = new ChatMessageCreatedEventListener(
                mock(AgentLoopExecutor.class), memory, new AgentLoopPromptProvider(),
                new AiAgentProperties(), mock(ChatSseService.class),
                new ConversationExecutionCoordinator(), mock(ObjectProvider.class));
        ReflectionTestUtils.setField(listener, "aiAgentTaskExecutor", (Executor) Runnable::run);
        ReflectionTestUtils.setField(listener, "aiTaskMapper", tasks);
        ReflectionTestUtils.setField(listener, "leaseKeeper", leases);
        ReflectionTestUtils.setField(listener, "completionService", completion);

        listener.submitRecovered(new ChatMessageCreatedEvent(7L, 1L, 2L, "推荐店铺", null, null));

        verify(heartbeat).cancelRenewal();
    }

    @Test
    void duplicateDeliverySkipsAgentWhenTaskAlreadyClaimedOrCompleted() {
        AgentLoopExecutor agent = mock(AgentLoopExecutor.class);
        ChatSseService sse = mock(ChatSseService.class);
        AiTaskMapper tasks = mock(AiTaskMapper.class);
        when(tasks.claim(eq(2L), anyString())).thenReturn(0);
        ChatMessageCreatedEventListener listener = new ChatMessageCreatedEventListener(
                agent, mock(ConversationMemoryService.class), new AgentLoopPromptProvider(),
                new AiAgentProperties(), sse, new ConversationExecutionCoordinator(),
                mock(ObjectProvider.class));
        ReflectionTestUtils.setField(listener, "aiAgentTaskExecutor", (Executor) Runnable::run);
        ReflectionTestUtils.setField(listener, "aiTaskMapper", tasks);
        ReflectionTestUtils.setField(listener, "leaseKeeper", mock(AiTaskLeaseKeeper.class));
        ChatMessageCreatedEvent event = new ChatMessageCreatedEvent(7L, 1L, 2L, "推荐店铺", null, null);

        listener.submitRecovered(event);
        listener.submitRecovered(event);

        verify(tasks, times(2)).claim(eq(2L), anyString());
        verifyNoInteractions(agent);
        verify(sse, never()).send(anyLong(), anyLong(), any(ChatStreamEvent.class));
    }
}

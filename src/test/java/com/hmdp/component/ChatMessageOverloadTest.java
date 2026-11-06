package com.hmdp.component;

import com.hmdp.ai.agent.loop.AgentLoopExecutor;
import com.hmdp.ai.agent.prompt.AgentLoopPromptProvider;
import com.hmdp.ai.config.AiAgentProperties;
import com.hmdp.ai.entity.AiMessage;
import com.hmdp.ai.memory.ConversationMemoryService;
import com.hmdp.ai.service.impl.AiMessageFailureRecorder;
import com.hmdp.ai.mapper.AiTaskMapper;
import com.hmdp.ai.service.impl.AiTaskLease;
import com.hmdp.ai.service.impl.AiTaskLeaseKeeper;
import com.hmdp.dto.ChatStreamEvent;
import com.hmdp.event.ChatMessageCreatedEvent;
import com.hmdp.service.ChatSseService;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatMessageOverloadTest {

    @Test
    @SuppressWarnings("unchecked")
    void fullQueuePersistsFallbackReplyAfterUserMessageWasCommitted() throws Exception {
        ChatSseService sse = mock(ChatSseService.class);
        AiMessageFailureRecorder recorder = mock(AiMessageFailureRecorder.class);
        ConversationExecutionCoordinator coordinator = mock(ConversationExecutionCoordinator.class);
        ChatMessageCreatedEventListener listener = new ChatMessageCreatedEventListener(
                mock(AgentLoopExecutor.class), mock(ConversationMemoryService.class),
                new AgentLoopPromptProvider(), new AiAgentProperties(), sse,
                coordinator, mock(ObjectProvider.class));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.initialize();
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            occupied.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(occupied.await(1, TimeUnit.SECONDS));
        ReflectionTestUtils.setField(listener, "aiAgentTaskExecutor", executor);
        ReflectionTestUtils.setField(listener, "failureRecorder", recorder);
        AiTaskMapper tasks = mock(AiTaskMapper.class);
        when(tasks.claim(eq(2L), anyString())).thenReturn(1);
        ReflectionTestUtils.setField(listener, "aiTaskMapper", tasks);
        AiTaskLeaseKeeper leaseKeeper = mock(AiTaskLeaseKeeper.class);
        doReturn(mock(AiTaskLease.class)).when(leaseKeeper).start(eq(2L), anyString());
        ReflectionTestUtils.setField(listener, "leaseKeeper", leaseKeeper);
        AiMessage assistant = new AiMessage().setId(3L).setRole("assistant")
                .setContent("抱歉，当前 AI 请求较多，请稍后重试。");
        when(recorder.recordBusy(eq(2L), anyString(), eq(1L), anyString())).thenReturn(assistant);

        try {
            listener.handleChatMessageCreated(new ChatMessageCreatedEvent(7L, 1L, 2L, "推荐店铺", null, null));
        } finally {
            release.countDown();
            executor.shutdown();
        }

        org.mockito.ArgumentCaptor<String> token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(recorder).recordBusy(eq(2L), token.capture(), eq(1L), eq(assistant.getContent()));
        verify(tasks, never()).releaseClaim(eq(2L), eq(token.getValue()));
        verifyNoInteractions(coordinator);
        var events = org.mockito.ArgumentCaptor.forClass(ChatStreamEvent.class);
        verify(sse, atLeastOnce()).send(eq(7L), eq(1L), events.capture());
        List<String> types = events.getAllValues().stream().map(ChatStreamEvent::getType).toList();
        assertTrue(types.containsAll(List.of("message", "answer_done", "done")));
    }
}

package com.hmdp.component;

import com.hmdp.ai.agent.loop.AgentLoopExecutor;
import com.hmdp.ai.agent.prompt.AgentLoopPromptProvider;
import com.hmdp.ai.config.AiAgentProperties;
import com.hmdp.ai.mapper.AiTaskMapper;
import com.hmdp.ai.memory.ConversationMemoryService;
import com.hmdp.ai.service.impl.AiTaskLeaseKeeper;
import com.hmdp.ai.service.impl.AiTaskLease;
import com.hmdp.ai.service.impl.AiTaskLeaseLostException;
import com.hmdp.ai.service.impl.AiTaskCompletionService;
import com.hmdp.event.ChatMessageCreatedEvent;
import com.hmdp.service.ChatSseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.chat.model.StreamingChatModel;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ChatMessageCreatedEventListenerRecoveryTest {
    @Test
    void lostLeaseDoesNotWriteFallbackOrCompleteTask() {
        AgentLoopExecutor agent = mock(AgentLoopExecutor.class);
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        AiTaskMapper tasks = mock(AiTaskMapper.class);
        AiTaskLeaseKeeper leaseKeeper = mock(AiTaskLeaseKeeper.class);
        AiTaskLease lease = mock(AiTaskLease.class);
        AiTaskCompletionService completion = mock(AiTaskCompletionService.class);
        ChatSseService sse = mock(ChatSseService.class);
        when(tasks.claim(eq(42L), anyString())).thenReturn(1);
        doReturn(lease).when(leaseKeeper).start(42L, "claim-42");
        doThrow(new AiTaskLeaseLostException(42L)).when(lease).assertActive();
        when(memory.loadRecentMessagesBefore(9L, 42L, 8)).thenReturn(List.of());
        ChatMessageCreatedEventListener listener = new ChatMessageCreatedEventListener(
                agent, memory, new AgentLoopPromptProvider(), new AiAgentProperties(), sse,
                new ConversationExecutionCoordinator(), mock(ObjectProvider.class));
        ReflectionTestUtils.setField(listener, "aiAgentTaskExecutor", (Executor) Runnable::run);
        ReflectionTestUtils.setField(listener, "aiTaskMapper", tasks);
        ReflectionTestUtils.setField(listener, "leaseKeeper", leaseKeeper);
        ReflectionTestUtils.setField(listener, "completionService", completion);

        listener.submitRecovered(new ChatMessageCreatedEvent(7L, 9L, 42L, "request", null, null), "claim-42");

        verifyNoInteractions(agent, completion);
        verify(sse, never()).send(anyLong(), anyLong(), any());
        verify(lease).cancelRenewal();
        verify(tasks, never()).findNextPending(anyLong());
    }

    @Test
    void renewsRecoveredLeaseWhileWaitingForConversationLock() throws Exception {
        AgentLoopExecutor agent = mock(AgentLoopExecutor.class);
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        AiTaskMapper tasks = mock(AiTaskMapper.class);
        AiTaskLeaseKeeper leaseKeeper = mock(AiTaskLeaseKeeper.class);
        AiTaskLease lease = mock(AiTaskLease.class);
        AiTaskLease secondLease = mock(AiTaskLease.class);
        AiTaskCompletionService completion = mock(AiTaskCompletionService.class);
        ChatSseService sse = mock(ChatSseService.class);
        CountDownLatch firstTaskEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);
        when(tasks.claim(eq(42L), anyString())).thenReturn(1);
        doReturn(lease, secondLease).when(leaseKeeper).start(eq(42L), anyString());
        when(memory.loadRecentMessagesBefore(9L, 42L, 8)).thenAnswer(invocation -> {
            firstTaskEntered.countDown();
            if (!releaseFirstTask.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("first task did not resume");
            }
            throw new IllegalStateException("stop before model call");
        });
        ChatMessageCreatedEventListener listener = new ChatMessageCreatedEventListener(
                agent, memory, new AgentLoopPromptProvider(), new AiAgentProperties(), sse,
                new ConversationExecutionCoordinator(), mock(ObjectProvider.class));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ReflectionTestUtils.setField(listener, "aiAgentTaskExecutor", (Executor) executor::execute);
        ReflectionTestUtils.setField(listener, "aiTaskMapper", tasks);
        ReflectionTestUtils.setField(listener, "leaseKeeper", leaseKeeper);
        ReflectionTestUtils.setField(listener, "completionService", completion);

        try {
            ChatMessageCreatedEvent event = new ChatMessageCreatedEvent(7L, 9L, 42L, "request", null, null);
            listener.submitRecovered(event);
            assertTrue(firstTaskEntered.await(2, TimeUnit.SECONDS));
            listener.submitRecovered(event, "claim-42");
            verify(leaseKeeper, times(2)).start(eq(42L), anyString());
            verifyNoInteractions(agent, completion);

            releaseFirstTask.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            verify(lease, atLeastOnce()).cancelRenewal();
            verify(secondLease, atLeastOnce()).cancelRenewal();
        } finally {
            releaseFirstTask.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void releasesPreclaimedRecoveryTaskWhenExecutorRejectsIt() {
        ChatMessageCreatedEventListener listener = new ChatMessageCreatedEventListener(
                mock(AgentLoopExecutor.class), mock(ConversationMemoryService.class),
                new AgentLoopPromptProvider(), new AiAgentProperties(), mock(ChatSseService.class),
                new ConversationExecutionCoordinator(), mock(ObjectProvider.class));
        AiTaskMapper tasks = mock(AiTaskMapper.class);
        AiTaskLeaseKeeper leaseKeeper = mock(AiTaskLeaseKeeper.class);
        AiTaskLease queuedLease = mock(AiTaskLease.class);
        when(tasks.claim(eq(42L), anyString())).thenReturn(1);
        ReflectionTestUtils.setField(listener, "aiAgentTaskExecutor", (Executor) command -> {
            throw new RejectedExecutionException("full");
        });
        ReflectionTestUtils.setField(listener, "aiTaskMapper", tasks);
        ReflectionTestUtils.setField(listener, "leaseKeeper", leaseKeeper);
        doReturn(queuedLease).when(leaseKeeper).start(42L, "claim-42");
        when(tasks.releaseClaim(42L, "claim-42")).thenReturn(1);

        listener.submitRecovered(new ChatMessageCreatedEvent(7L, 9L, 42L, "request", null, null), "claim-42");

        verify(tasks).releaseClaim(42L, "claim-42");
        verify(leaseKeeper).start(42L, "claim-42");
        verify(queuedLease).cancelRenewal();
    }

}

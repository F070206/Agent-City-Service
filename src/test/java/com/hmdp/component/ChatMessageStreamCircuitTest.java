package com.hmdp.component;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.hmdp.ai.agent.loop.AgentLoopExecutor;
import com.hmdp.ai.agent.loop.AgentLoopRequest;
import com.hmdp.ai.agent.loop.AgentLoopResult;
import com.hmdp.ai.agent.prompt.AgentLoopPromptProvider;
import com.hmdp.ai.config.AiAgentProperties;
import com.hmdp.ai.service.impl.AiTaskLease;
import com.hmdp.ai.memory.ConversationMemoryService;
import com.hmdp.dto.ChatStreamEvent;
import com.hmdp.event.ChatMessageCreatedEvent;
import com.hmdp.service.ChatSseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatMessageStreamCircuitTest {
    @AfterEach
    void clearRules() {
        DegradeRuleManager.loadRules(List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamFailureOpensCircuitAndReusesFallbackAnswer() {
        StreamingChatModel model = mock(StreamingChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("model unavailable")));
        ObjectProvider<StreamingChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        ChatSseService sse = mock(ChatSseService.class);
        ChatMessageCreatedEventListener listener = new ChatMessageCreatedEventListener(
                mock(AgentLoopExecutor.class), mock(ConversationMemoryService.class),
                new AgentLoopPromptProvider(), new AiAgentProperties(), sse,
                mock(ConversationExecutionCoordinator.class), provider);
        AgentLoopRequest request = new AgentLoopRequest();
        request.setPrompt("推荐店铺");
        AgentLoopResult result = new AgentLoopResult();
        result.setToolLogs(List.of());
        ChatMessageCreatedEvent event = new ChatMessageCreatedEvent(7L, 1L, 2L, "推荐店铺", null, null);
        DegradeRuleManager.loadRules(List.of(new DegradeRule("ai:model:stream")
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO).setCount(0.5)
                .setMinRequestAmount(1).setStatIntervalMs(1000).setTimeWindow(10)));

        String first = ReflectionTestUtils.invokeMethod(listener, "streamFinalAnswer",
                event, request, result, "本地候选答案", mock(AiTaskLease.class));
        String second = ReflectionTestUtils.invokeMethod(listener, "streamFinalAnswer",
                event, request, result, "本地候选答案", mock(AiTaskLease.class));

        assertEquals("本地候选答案", first);
        assertEquals("本地候选答案", second);
        verify(model, times(1)).stream(any(Prompt.class));
        verify(sse, atLeastOnce()).send(eq(7L), eq(1L), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void partialStreamFailureCompletesOnlyMissingCandidateText() {
        StreamingChatModel model = mock(StreamingChatModel.class);
        ChatResponse firstChunk = new ChatResponse(List.of(new Generation(new AssistantMessage("前缀"))));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(firstChunk)
                .concatWith(Flux.error(new IllegalStateException("stream interrupted"))));
        ObjectProvider<StreamingChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        ChatSseService sse = mock(ChatSseService.class);
        ChatMessageCreatedEventListener listener = new ChatMessageCreatedEventListener(
                mock(AgentLoopExecutor.class), mock(ConversationMemoryService.class),
                new AgentLoopPromptProvider(), new AiAgentProperties(), sse,
                mock(ConversationExecutionCoordinator.class), provider);
        AgentLoopRequest request = new AgentLoopRequest();
        request.setPrompt("推荐店铺");
        AgentLoopResult result = new AgentLoopResult();
        result.setToolLogs(List.of());
        ChatMessageCreatedEvent event = new ChatMessageCreatedEvent(7L, 1L, 2L, "推荐店铺", null, null);

        String answer = ReflectionTestUtils.invokeMethod(listener, "streamFinalAnswer",
                event, request, result, "前缀补全", mock(AiTaskLease.class));

        org.mockito.ArgumentCaptor<ChatStreamEvent> events = org.mockito.ArgumentCaptor.forClass(ChatStreamEvent.class);
        verify(sse, atLeastOnce()).send(eq(7L), eq(1L), events.capture());
        String deltas = events.getAllValues().stream()
                .filter(item -> "answer_delta".equals(item.getType()))
                .map(item -> (String) ((Map<String, Object>) item.getPayload()).get("delta"))
                .collect(Collectors.joining());
        assertEquals("前缀补全", answer);
        assertEquals("前缀补全", deltas);
    }

    @Test
    @SuppressWarnings("unchecked")
    void slowContinuousStreamFallsBackAtTotalTimeout() {
        StreamingChatModel model = mock(StreamingChatModel.class);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.interval(Duration.ofMillis(1500))
                .take(4)
                .doOnCancel(() -> cancelled.set(true))
                .map(index -> new ChatResponse(List.of(new Generation(
                        new AssistantMessage("慢速片段"))))));
        ObjectProvider<StreamingChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        ChatSseService sse = mock(ChatSseService.class);
        AiAgentProperties properties = new AiAgentProperties();
        properties.getStreaming().setTimeoutSeconds(5);
        ChatMessageCreatedEventListener listener = new ChatMessageCreatedEventListener(
                mock(AgentLoopExecutor.class), mock(ConversationMemoryService.class),
                new AgentLoopPromptProvider(), properties, sse,
                mock(ConversationExecutionCoordinator.class), provider);
        AgentLoopRequest request = new AgentLoopRequest();
        request.setPrompt("推荐店铺");
        AgentLoopResult result = new AgentLoopResult();
        result.setToolLogs(List.of());
        ChatMessageCreatedEvent event = new ChatMessageCreatedEvent(7L, 1L, 2L, "推荐店铺", null, null);

        String answer = ReflectionTestUtils.invokeMethod(listener, "streamFinalAnswer",
                event, request, result, "本地候选答案", mock(AiTaskLease.class));

        org.mockito.ArgumentCaptor<ChatStreamEvent> events = org.mockito.ArgumentCaptor.forClass(ChatStreamEvent.class);
        verify(sse, atLeastOnce()).send(eq(7L), eq(1L), events.capture());
        org.junit.jupiter.api.Assertions.assertTrue(answer.startsWith("慢速片段"));
        org.junit.jupiter.api.Assertions.assertTrue(answer.endsWith("本地候选答案"));
        org.junit.jupiter.api.Assertions.assertTrue(events.getAllValues().stream()
                .filter(item -> "status".equals(item.getType()))
                .map(ChatStreamEvent::getPayload)
                .filter(Map.class::isInstance)
                .map(payload -> ((Map<String, Object>) payload).get("status"))
                .anyMatch("stream_recover"::equals));
        org.junit.jupiter.api.Assertions.assertTrue(cancelled.get());
    }
}

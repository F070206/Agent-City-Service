package com.hmdp.component;

import com.hmdp.ai.agent.loop.AgentLoopExecution;
import com.hmdp.ai.agent.loop.AgentLoopExecutor;
import com.hmdp.ai.agent.loop.AgentLoopRequest;
import com.hmdp.ai.agent.loop.AgentLoopResult;
import com.hmdp.ai.agent.loop.AgentLoopStatus;
import com.hmdp.ai.agent.loop.AgentLoopTrace;
import com.hmdp.ai.agent.loop.ToolExecutionLog;
import com.hmdp.ai.agent.prompt.AgentLoopPromptProvider;
import com.hmdp.ai.config.AiAgentProperties;
import com.hmdp.ai.config.AiDependencyGuard;
import com.hmdp.ai.service.impl.AiTaskLease;
import com.hmdp.ai.service.impl.AiTaskLeaseLostException;
import com.hmdp.ai.service.impl.AiMessageFailureRecorder;
import com.hmdp.ai.service.impl.AiTaskCompletionService;
import com.hmdp.ai.service.impl.AiTaskLeaseKeeper;
import com.hmdp.ai.mapper.AiTaskMapper;
import com.hmdp.ai.entity.AiTask;
import com.hmdp.ai.dto.MessageDTO;
import com.hmdp.ai.entity.AiMessage;
import com.hmdp.ai.memory.ConversationMemoryService;
import com.hmdp.dto.ChatStreamEvent;
import com.hmdp.event.ChatMessageCreatedEvent;
import com.hmdp.service.ChatSseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;
import jakarta.annotation.Resource;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageCreatedEventListener {

    private final AgentLoopExecutor agentLoopExecutor;

    private final ConversationMemoryService conversationMemoryService;

    private final AgentLoopPromptProvider promptProvider;

    private final AiAgentProperties aiAgentProperties;

    private final ChatSseService chatSseService;

    private final ConversationExecutionCoordinator executionCoordinator;

    private final ObjectProvider<StreamingChatModel> streamingChatModelProvider;

    @Resource(name = "aiAgentTaskExecutor")
    private Executor aiAgentTaskExecutor;

    @Resource
    private AiMessageFailureRecorder failureRecorder;

    @Resource
    private AiTaskMapper aiTaskMapper;

    @Resource
    private AiTaskCompletionService completionService;

    @Resource
    private AiTaskLeaseKeeper leaseKeeper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleChatMessageCreated(ChatMessageCreatedEvent event) {
        submit(event, false, null);
    }

    public void submitRecovered(ChatMessageCreatedEvent event) {
        submit(event, true, null);
    }

    public void submitRecovered(ChatMessageCreatedEvent event, String claimToken) {
        submit(event, true, claimToken);
    }

    private void submit(ChatMessageCreatedEvent event, boolean recovered, String claimToken) {
        String token = claimToken == null ? UUID.randomUUID().toString() : claimToken;
        if (claimToken == null && aiTaskMapper.claim(event.getChatMessageId(), token) != 1) {
            return;
        }
        AiTaskLease queuedLease;
        try {
            queuedLease = leaseKeeper.start(event.getChatMessageId(), token);
        } catch (RuntimeException e) {
            releaseRecoveredClaim(event, token, null);
            throw e;
        }
        AiTaskLease recoveryLease = queuedLease;
        try {
            aiAgentTaskExecutor.execute(() -> executionCoordinator.runSerial(
                    event.getConversationId(), () -> doHandle(event, token, recoveryLease)));
        } catch (RejectedExecutionException e) {
            if (recovered) {
                log.warn("AI 补扫任务队列已满，下轮继续重试，messageId={}", event.getChatMessageId());
                releaseRecoveredClaim(event, token, recoveryLease);
                return;
            }
            log.warn("AI 任务队列已满，conversationId={}, messageId={}",
                    event.getConversationId(), event.getChatMessageId(), e);
            String reply = "抱歉，当前 AI 请求较多，请稍后重试。";
            AiMessage assistantMessage;
            try {
                assistantMessage = failureRecorder.recordBusy(event.getChatMessageId(), token,
                        event.getConversationId(), reply);
            } catch (Exception recordError) {
                releaseRecoveredClaim(event, token, recoveryLease);
                log.error("AI 任务拒绝后保存兜底回复失败，conversationId={}", event.getConversationId(), recordError);
                sendError(event, reply);
                throw recordError;
            }
            if (recoveryLease != null) {
                recoveryLease.cancelRenewal();
            }
            sendMessage(event, MessageDTO.from(assistantMessage));
            sendAnswerDone(event, assistantMessage, null, reply);
            sendDone(event, null);
            submitNextPending(event.getConversationId());
        } catch (RuntimeException e) {
            releaseRecoveredClaim(event, token, recoveryLease);
            throw e;
        }
    }

    private void releaseRecoveredClaim(ChatMessageCreatedEvent event, String claimToken,
            AiTaskLease queuedLease) {
        if (queuedLease != null) {
            queuedLease.cancelRenewal();
        }
        if (claimToken != null) {
            int released = aiTaskMapper.releaseClaim(event.getChatMessageId(), claimToken);
            if (released != 1) {
                log.warn("AI 补扫队列拒绝后未能释放任务租约，messageId={}", event.getChatMessageId());
            }
        }
    }

    private void doHandle(ChatMessageCreatedEvent event, String claimedToken, AiTaskLease queuedLease) {
        String token = claimedToken;
        AiTaskLease lease = queuedLease;
        boolean taskCompleted = false;
        try {
            if (lease == null) {
                lease = leaseKeeper.start(event.getChatMessageId(), token);
            }
            lease.assertActive();
            sendStatus(event, "queued", "消息已入队，等待 Agent 处理");
            List<Message> historyMessages = conversationMemoryService.loadRecentMessagesBefore(
                    event.getConversationId(),
                    event.getChatMessageId(),
                    aiAgentProperties.getMemory().getWindowSize()
            );
            lease.assertActive();

            AgentLoopRequest loopRequest = new AgentLoopRequest();
            loopRequest.setPrompt(event.getContent());
            loopRequest.setX(event.getX());
            loopRequest.setY(event.getY());
            loopRequest.setHistoryMessages(historyMessages);

            AgentLoopResult loopResult = agentLoopExecutor.execute(
                    loopRequest, new StreamingLoopObserver(event), lease);
            lease.assertActive();
            String assistantReply = buildAssistantReply(loopResult.getAgentLoopExecution());
            sendStatus(event, "answering", "工具执行完成，正在流式生成最终回答");
            String finalReply = streamFinalAnswer(event, loopRequest, loopResult, assistantReply, lease);
            lease.assertActive();
            AiMessage assistantMessage = completionService.complete(
                    event.getChatMessageId(), token, event.getConversationId(), finalReply,
                    loopResult.getScene(), event.getContent());
            taskCompleted = true;
            sendMessage(event, MessageDTO.from(assistantMessage));
            sendAnswerDone(event, assistantMessage, loopResult.getAgentLoopExecution(), finalReply);
            sendDone(event, loopResult.getAgentLoopExecution());
        }
        catch (AiTaskLeaseLostException ex) {
            log.warn("AI 任务租约失效，停止旧执行并等待恢复任务接管，messageId={}", event.getChatMessageId());
        }
        catch (Exception ex) {
            log.error("Async chat execution failed, conversationId={}", event.getConversationId(), ex);
            String reply = "抱歉，当前异步处理消息时出现异常，请稍后重试。";
            AiMessage assistantMessage;
            if (taskCompleted) {
                assistantMessage = null;
            } else {
                assistantMessage = completionService.complete(
                        event.getChatMessageId(), token, event.getConversationId(), reply,
                        null, event.getContent());
                taskCompleted = true;
            }
            sendError(event, reply);
            if (assistantMessage != null) {
                sendMessage(event, MessageDTO.from(assistantMessage));
            }
            sendDone(event, null);
        } finally {
            if (lease != null) {
                lease.cancelRenewal();
            }
            if (taskCompleted) {
                submitNextPending(event.getConversationId());
            }
        }
    }

    private void submitNextPending(Long conversationId) {
        AiTask next = aiTaskMapper.findNextPending(conversationId);
        if (next == null) {
            return;
        }
        submit(new ChatMessageCreatedEvent(next.getUserId(), next.getConversationId(),
                next.getMessageId(), next.getContent(), next.getX(), next.getY()), true, null);
    }

    private String streamFinalAnswer(ChatMessageCreatedEvent event,
                                     AgentLoopRequest request,
                                     AgentLoopResult loopResult,
                                     String fallbackAnswer,
                                     AiTaskLease lease) {
        lease.assertActive();
        String safeFallbackAnswer = StringUtils.hasText(fallbackAnswer)
                ? fallbackAnswer
                : "抱歉，当前多轮推理没有产生可用结果，请稍后重试。";

        StreamingChatModel streamingChatModel = streamingChatModelProvider.getIfAvailable();
        if (!aiAgentProperties.getStreaming().isEnabled() || streamingChatModel == null) {
            emitAnswerByChunks(event, safeFallbackAnswer, 0, lease);
            return safeFallbackAnswer;
        }

        StringBuilder builder = new StringBuilder();
        AtomicInteger chunkIndex = new AtomicInteger();
        try {
            Prompt prompt = new Prompt(
                    List.of(
                            new SystemMessage(promptProvider.finalAnswerSystemPrompt()),
                            new UserMessage(promptProvider.buildFinalAnswerPrompt(request, safeFallbackAnswer, loopResult.getToolLogs()))
                    ),
                    OpenAiChatOptions.builder()
                            .internalToolExecutionEnabled(false)
                            .parallelToolCalls(false)
                            .build()
            );

            Duration streamTimeout = Duration.ofSeconds(
                    Math.max(aiAgentProperties.getStreaming().getTimeoutSeconds(), 5));
            AiDependencyGuard.consume("ai:model:stream", "ai:model:stream:read", () -> {
                long deadlineNanos = System.nanoTime() + streamTimeout.toNanos();
                return streamingChatModel.stream(prompt)
                        .timeout(Mono.delay(streamTimeout), response -> Mono.delay(Duration.ofNanos(
                                Math.max(0L, deadlineNanos - System.nanoTime()))))
                        .toIterable();
            }, response -> {
                lease.assertActive();
                String delta = extractDelta(response);
                if (!StringUtils.hasText(delta)) {
                    return;
                }
                builder.append(delta);
                sendAnswerDelta(event, delta, chunkIndex.getAndIncrement());
            });
            lease.assertActive();

            String content = builder.toString().trim();
            if (StringUtils.hasText(content)) {
                return content;
            }

            sendStatus(event, "stream_fallback", "模型流式返回空内容，已切换兜底补全");
            emitAnswerByChunks(event, safeFallbackAnswer, chunkIndex.get(), lease);
            return safeFallbackAnswer;
        }
        catch (AiTaskLeaseLostException ex) {
            throw ex;
        }
        catch (Exception ex) {
            log.warn("Streaming final answer failed, conversationId={}", event.getConversationId(), ex);
            if (builder.length() > 0) {
                sendStatus(event, "stream_recover", "流式输出中断，正在补全剩余内容");
                String partial = builder.toString();
                if (safeFallbackAnswer.startsWith(partial)) {
                    String remaining = safeFallbackAnswer.substring(partial.length());
                    emitAnswerByChunks(event, remaining, chunkIndex.get(), lease);
                    return safeFallbackAnswer;
                }
                String supplement = "\n\n" + safeFallbackAnswer;
                emitAnswerByChunks(event, supplement, chunkIndex.get(), lease);
                return partial + supplement;
            }
            sendStatus(event, "stream_fallback", "流式输出失败，已切换兜底回答");
            emitAnswerByChunks(event, safeFallbackAnswer, chunkIndex.get(), lease);
            return safeFallbackAnswer;
        }
    }

    private String buildAssistantReply(AgentLoopExecution execution) {
        if (execution == null) {
            return "抱歉，当前多轮推理没有产生可用结果，请稍后重试。";
        }
        if (StringUtils.hasText(execution.getFinalAnswer())) {
            return execution.getFinalAnswer();
        }
        if (execution.getStatus() == AgentLoopStatus.ERROR && StringUtils.hasText(execution.getErrorMessage())) {
            return "抱歉，多轮推理过程中出现错误，请稍后重试。";
        }
        return "抱歉，当前多轮推理没有产生可用结果，请稍后重试。";
    }

    private void sendStatus(ChatMessageCreatedEvent event, String status, String detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("detail", detail);
        chatSseService.send(event.getUserId(), event.getConversationId(), ChatStreamEvent.builder()
                .type("status")
                .conversationId(event.getConversationId())
                .payload(payload)
                .createdTime(LocalDateTime.now())
                .build());
    }

    private void sendMessage(ChatMessageCreatedEvent event, MessageDTO message) {
        chatSseService.send(event.getUserId(), event.getConversationId(), ChatStreamEvent.builder()
                .type("message")
                .conversationId(event.getConversationId())
                .payload(message)
                .createdTime(LocalDateTime.now())
                .build());
    }

    private void sendAnswerDelta(ChatMessageCreatedEvent event, String delta, int chunkIndex) {
        chatSseService.send(event.getUserId(), event.getConversationId(), ChatStreamEvent.builder()
                .type("answer_delta")
                .conversationId(event.getConversationId())
                .payload(Map.of(
                        "delta", delta,
                        "chunkIndex", chunkIndex
                ))
                .createdTime(LocalDateTime.now())
                .build());
    }

    private void sendAnswerDone(ChatMessageCreatedEvent event,
                                AiMessage assistantMessage,
                                AgentLoopExecution execution,
                                String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", assistantMessage.getId());
        payload.put("content", content);
        payload.put("status", execution == null ? "finished" : execution.getStatus().name().toLowerCase());
        payload.put("terminatedByTool", execution != null && execution.isTerminatedByTool());
        chatSseService.send(event.getUserId(), event.getConversationId(), ChatStreamEvent.builder()
                .type("answer_done")
                .conversationId(event.getConversationId())
                .payload(payload)
                .createdTime(LocalDateTime.now())
                .build());
    }

    private void sendError(ChatMessageCreatedEvent event, String detail) {
        chatSseService.send(event.getUserId(), event.getConversationId(), ChatStreamEvent.builder()
                .type("error")
                .conversationId(event.getConversationId())
                .payload(Map.of("detail", detail))
                .createdTime(LocalDateTime.now())
                .build());
    }

    private void sendDone(ChatMessageCreatedEvent event, AgentLoopExecution execution) {
        String status = execution == null ? "finished" : execution.getStatus().name().toLowerCase();
        chatSseService.send(event.getUserId(), event.getConversationId(), ChatStreamEvent.builder()
                .type("done")
                .conversationId(event.getConversationId())
                .payload(Map.of("status", status))
                .createdTime(LocalDateTime.now())
                .build());
    }

    private String extractDelta(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private void emitAnswerByChunks(ChatMessageCreatedEvent event, String content, int startIndex, AiTaskLease lease) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        int chunkSize = Math.max(aiAgentProperties.getStreaming().getFallbackChunkSize(), 8);
        int chunkIndex = startIndex;
        for (int cursor = 0; cursor < content.length(); cursor += chunkSize) {
            lease.assertActive();
            String delta = content.substring(cursor, Math.min(cursor + chunkSize, content.length()));
            sendAnswerDelta(event, delta, chunkIndex++);
        }
    }

    private class StreamingLoopObserver implements AgentLoopObserver {

        private final ChatMessageCreatedEvent event;

        private StreamingLoopObserver(ChatMessageCreatedEvent event) {
            this.event = event;
        }

        @Override
        public void onTrace(AgentLoopTrace trace) {
            if (trace == null || trace.getStatus() == AgentLoopStatus.IDLE) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", trace.getStatus().name().toLowerCase());
            payload.put("detail", trace.getDetail());
            payload.put("step", trace.getStep());
            if (!trace.getToolNames().isEmpty()) {
                payload.put("toolNames", trace.getToolNames());
            }
            chatSseService.send(event.getUserId(), event.getConversationId(), ChatStreamEvent.builder()
                    .type("status")
                    .conversationId(event.getConversationId())
                    .payload(payload)
                    .createdTime(LocalDateTime.now())
                    .build());
        }

        @Override
        public void onToolExecuted(ToolExecutionLog log) {
            AiMessage toolMessage = conversationMemoryService.appendToolMessage(
                    event.getConversationId(),
                    log.getToolName(),
                    log.getPayload()
            );
            sendMessage(event, MessageDTO.from(toolMessage));
        }
    }
}

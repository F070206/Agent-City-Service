package com.hmdp.ai.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.agent.prompt.AgentLoopPromptProvider;
import com.hmdp.ai.config.AiAgentProperties;
import com.hmdp.ai.service.impl.AiTaskLease;
import com.hmdp.ai.service.impl.AiTaskLeaseLostException;
import com.hmdp.ai.tool.AgentLoopControlTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ManualToolAgentLoopExecutorTest {
    @Test
    void truncatesOversizedToolResultBeforeItEntersTheNextModelPrompt() {
        AtomicInteger calls = new AtomicInteger();
        List<Prompt> prompts = new ArrayList<>();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                prompts.add(prompt);
                if (prompts.size() == 1) {
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(
                            "", java.util.Map.of(), List.of(new AssistantMessage.ToolCall(
                            "call-large", "function", "large", "{}"))))));
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("完成"))));
            }
        };
        AgentLoopToolRegistry registry = new AgentLoopToolRegistry(Arrays.asList(
                ToolCallbacks.from(new LargeResultTool(calls), new AgentLoopControlTools())
        ));
        ManualToolAgentLoopExecutor executor = new ManualToolAgentLoopExecutor(
                chatModel, registry, new AgentLoopPromptProvider(), new AiAgentProperties(), new ObjectMapper());

        AgentLoopExecution execution = executor.execute(new AgentLoopRequest(), new ArrayList<>());

        ToolResponseMessage response = assertInstanceOf(ToolResponseMessage.class,
                prompts.get(1).getInstructions().stream()
                        .filter(ToolResponseMessage.class::isInstance).findFirst().orElseThrow());
        assertTrue(response.getResponses().get(0).responseData().length() <= 8_000);
        assertTrue(response.getResponses().get(0).responseData().contains("工具结果已截断"));
        assertEquals(AgentLoopStatus.FINISHED, execution.getStatus());
    }

    @Test
    void stopsCallingToolsWhenCumulativeToolContextBudgetIsConsumed() {
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                modelCalls.incrementAndGet();
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "", java.util.Map.of(), List.of(new AssistantMessage.ToolCall(
                        "call-large", "function", "large", "{}"))))));
            }
        };
        AgentLoopToolRegistry registry = new AgentLoopToolRegistry(Arrays.asList(
                ToolCallbacks.from(new LargeResultTool(toolCalls), new AgentLoopControlTools())
        ));
        AiAgentProperties properties = new AiAgentProperties();
        properties.getMultiAgent().setMaxSteps(10);
        ManualToolAgentLoopExecutor executor = new ManualToolAgentLoopExecutor(
                chatModel, registry, new AgentLoopPromptProvider(), properties, new ObjectMapper());

        AgentLoopExecution execution = executor.execute(new AgentLoopRequest(), new ArrayList<>());

        assertEquals(3, toolCalls.get());
        assertEquals(4, modelCalls.get());
        assertEquals(AgentLoopStatus.FINISHED, execution.getStatus());
        assertTrue(execution.getFinalAnswer().contains("已有内容"));
    }


    @Test
    void modelFailureKeepsInternalErrorOutOfUserAnswer() {
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("internal-model-endpoint-secret");
            }
        };
        AgentLoopToolRegistry registry = new AgentLoopToolRegistry(Arrays.asList(
                ToolCallbacks.from(new TestLoopTools(), new AgentLoopControlTools())
        ));
        ManualToolAgentLoopExecutor executor = new ManualToolAgentLoopExecutor(
                chatModel, registry, new AgentLoopPromptProvider(), new AiAgentProperties(), new ObjectMapper());
        AgentLoopRequest request = new AgentLoopRequest();
        request.setPrompt("推荐店铺");

        AgentLoopExecution execution = executor.execute(request, new ArrayList<>());

        assertEquals(AgentLoopStatus.ERROR, execution.getStatus());
        assertTrue(execution.getErrorMessage().contains("internal-model-endpoint-secret"));
        assertFalse(execution.getFinalAnswer().contains("internal-model-endpoint-secret"));
    }

    @Test
    void emptyModelResponseBecomesControlledAgentError() {
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of());
            }
        };
        AgentLoopToolRegistry registry = new AgentLoopToolRegistry(Arrays.asList(
                ToolCallbacks.from(new TestLoopTools(), new AgentLoopControlTools())
        ));
        ManualToolAgentLoopExecutor executor = new ManualToolAgentLoopExecutor(
                chatModel, registry, new AgentLoopPromptProvider(), new AiAgentProperties(), new ObjectMapper());

        AgentLoopExecution execution = executor.execute(new AgentLoopRequest(), new ArrayList<>());

        assertEquals(AgentLoopStatus.ERROR, execution.getStatus());
        assertTrue(execution.getErrorMessage().contains("未返回可用结果"));
        assertEquals("抱歉，当前多轮推理过程中模型调用失败，请稍后重试。", execution.getFinalAnswer());
    }

    @Test
    void shouldRetryWhenModelReturnsBlankResponse() {
        AtomicInteger callCount = new AtomicInteger();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                if (callCount.incrementAndGet() == 1) {
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(""))));
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "",
                        java.util.Map.of(),
                        List.of(new AssistantMessage.ToolCall("call-2", "function", "terminate", "{\"answer\":\"重试后成功\",\"reason\":\"补充约束后模型正常结束\"}"))
                ))));
            }
        };

        AgentLoopToolRegistry registry = new AgentLoopToolRegistry(Arrays.asList(
                ToolCallbacks.from(new TestLoopTools(), new AgentLoopControlTools())
        ));
        AiAgentProperties properties = new AiAgentProperties();
        properties.getMultiAgent().setMaxSteps(5);
        ManualToolAgentLoopExecutor executor = new ManualToolAgentLoopExecutor(
                chatModel,
                registry,
                new AgentLoopPromptProvider(),
                properties,
                new ObjectMapper()
        );

        AgentLoopRequest request = new AgentLoopRequest();
        request.setPrompt("帮我处理一个问题");
        AgentLoopExecution execution = executor.execute(request, new ArrayList<>());

        assertEquals(AgentLoopStatus.FINISHED, execution.getStatus());
        assertEquals("重试后成功", execution.getFinalAnswer());
        assertEquals(2, execution.getTotalSteps());
    }

    @Test
    void shouldFinishViaTerminateToolWithinLoop() {
        AtomicInteger callCount = new AtomicInteger();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                if (callCount.incrementAndGet() == 1) {
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(
                            "",
                            java.util.Map.of(),
                            List.of(new AssistantMessage.ToolCall("call-1", "function", "echo", "{\"text\":\"hello\"}"))
                    ))));
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "",
                        java.util.Map.of(),
                        List.of(new AssistantMessage.ToolCall("call-2", "function", "terminate", "{\"answer\":\"最终答案\",\"reason\":\"信息已经足够\"}"))
                ))));
            }
        };

        AgentLoopToolRegistry registry = new AgentLoopToolRegistry(Arrays.asList(
                ToolCallbacks.from(new TestLoopTools(), new AgentLoopControlTools())
        ));
        AiAgentProperties properties = new AiAgentProperties();
        properties.getMultiAgent().setMaxSteps(20);
        ManualToolAgentLoopExecutor executor = new ManualToolAgentLoopExecutor(
                chatModel,
                registry,
                new AgentLoopPromptProvider(),
                properties,
                new ObjectMapper()
        );

        AgentLoopRequest request = new AgentLoopRequest();
        request.setPrompt("帮我一步步处理");
        AgentLoopExecution execution = executor.execute(request, new ArrayList<>());

        assertEquals(AgentLoopStatus.FINISHED, execution.getStatus());
        assertTrue(execution.isTerminatedByTool());
        assertEquals("最终答案", execution.getFinalAnswer());
        assertEquals(2, execution.getTotalSteps());
        assertTrue(execution.getTraces().stream().anyMatch(trace -> trace.getStatus() == AgentLoopStatus.THINKING));
        assertTrue(execution.getTraces().stream().anyMatch(trace -> trace.getStatus() == AgentLoopStatus.EXECUTING));
        assertTrue(execution.getTraces().stream().anyMatch(trace -> trace.getStatus() == AgentLoopStatus.FINISHED));
    }

    @Test
    void shouldStopWhenExceedingMaxSteps() {
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "",
                        java.util.Map.of(),
                        List.of(new AssistantMessage.ToolCall("call-loop", "function", "echo", "{\"text\":\"loop\"}"))
                ))));
            }
        };

        AgentLoopToolRegistry registry = new AgentLoopToolRegistry(Arrays.asList(
                ToolCallbacks.from(new TestLoopTools(), new AgentLoopControlTools())
        ));
        AiAgentProperties properties = new AiAgentProperties();
        properties.getMultiAgent().setMaxSteps(3);
        ManualToolAgentLoopExecutor executor = new ManualToolAgentLoopExecutor(
                chatModel,
                registry,
                new AgentLoopPromptProvider(),
                properties,
                new ObjectMapper()
        );

        AgentLoopRequest request = new AgentLoopRequest();
        request.setPrompt("一直循环");
        AgentLoopExecution execution = executor.execute(request, new ArrayList<>());

        assertEquals(AgentLoopStatus.ERROR, execution.getStatus());
        assertFalse(execution.isTerminatedByTool());
        assertTrue(execution.getFinalAnswer().contains("3 轮内仍未完成"));
        assertEquals(3, execution.getTotalSteps());
    }

    @Test
    void configuredStepCountCannotOverflowTheLoopCounter() {
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
            }
        };
        AiAgentProperties properties = new AiAgentProperties();
        properties.getMultiAgent().setMaxSteps(Integer.MAX_VALUE);
        ManualToolAgentLoopExecutor executor = new ManualToolAgentLoopExecutor(
                chatModel,
                new AgentLoopToolRegistry(Arrays.asList(
                        ToolCallbacks.from(new TestLoopTools(), new AgentLoopControlTools())
                )),
                new AgentLoopPromptProvider(), properties, new ObjectMapper());

        AgentLoopExecution execution = executor.execute(new AgentLoopRequest(), new ArrayList<>());

        assertEquals(50, execution.getMaxSteps());
        assertEquals(1, execution.getTotalSteps());
        assertEquals(AgentLoopStatus.FINISHED, execution.getStatus());
    }

    @Test
    void unregisteredToolIsRejectedBeforeAnyRegisteredToolRuns() {
        AtomicInteger toolCalls = new AtomicInteger();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "",
                        java.util.Map.of(),
                        List.of(new AssistantMessage.ToolCall("call-unknown", "function", "unregistered_side_effect", "{}"))
                ))));
            }
        };

        AgentLoopToolRegistry registry = new AgentLoopToolRegistry(Arrays.asList(
                ToolCallbacks.from(new TestLoopTools(toolCalls), new AgentLoopControlTools())
        ));
        ManualToolAgentLoopExecutor executor = new ManualToolAgentLoopExecutor(
                chatModel, registry, new AgentLoopPromptProvider(), new AiAgentProperties(), new ObjectMapper());
        AgentLoopRequest request = new AgentLoopRequest();
        request.setPrompt("执行一个工具");
        List<ToolExecutionLog> toolLogs = new ArrayList<>();

        AgentLoopExecution execution = executor.execute(request, toolLogs);

        assertEquals(AgentLoopStatus.ERROR, execution.getStatus());
        assertTrue(execution.getErrorMessage().contains("未注册工具"));
        assertEquals(0, toolCalls.get());
        assertTrue(toolLogs.stream().noneMatch(log -> "unregistered_side_effect".equals(log.getToolName())));
        assertEquals(1, execution.getTotalSteps());
    }

    @Test
    void multipleToolCallsAreRejectedBeforeAnyToolRuns() {
        AtomicInteger toolCalls = new AtomicInteger();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "",
                        java.util.Map.of(),
                        List.of(
                                new AssistantMessage.ToolCall("call-echo", "function", "echo",
                                        "{\"text\":\"should-not-run\"}"),
                                new AssistantMessage.ToolCall("call-unknown", "function", "unregistered_side_effect", "{}"))
                ))));
            }
        };
        AgentLoopToolRegistry registry = new AgentLoopToolRegistry(Arrays.asList(
                ToolCallbacks.from(new TestLoopTools(toolCalls), new AgentLoopControlTools())
        ));
        ManualToolAgentLoopExecutor executor = new ManualToolAgentLoopExecutor(
                chatModel, registry, new AgentLoopPromptProvider(), new AiAgentProperties(), new ObjectMapper());
        AgentLoopRequest request = new AgentLoopRequest();
        request.setPrompt("执行一个工具");

        AgentLoopExecution execution = executor.execute(request, new ArrayList<>());

        assertEquals(AgentLoopStatus.ERROR, execution.getStatus());
        assertEquals(0, toolCalls.get());
        assertTrue(execution.getErrorMessage().contains("单轮请求了多个工具调用"));
    }

    @Test
    void lostLeaseAfterModelResponsePreventsToolExecution() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                modelCalls.incrementAndGet();
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "",
                        java.util.Map.of(),
                        List.of(new AssistantMessage.ToolCall("call-1", "function", "echo", "{\"text\":\"hello\"}"))
                ))));
            }
        };
        AiTaskLease lease = mock(AiTaskLease.class);
        doNothing().doThrow(new AiTaskLeaseLostException(42L)).when(lease).assertActive();
        AgentLoopToolRegistry registry = new AgentLoopToolRegistry(Arrays.asList(
                ToolCallbacks.from(new TestLoopTools(toolCalls), new AgentLoopControlTools())
        ));
        ManualToolAgentLoopExecutor executor = new ManualToolAgentLoopExecutor(
                chatModel, registry, new AgentLoopPromptProvider(), new AiAgentProperties(), new ObjectMapper());
        AgentLoopRequest request = new AgentLoopRequest();
        request.setPrompt("推荐店铺");

        org.junit.jupiter.api.Assertions.assertThrows(AiTaskLeaseLostException.class,
                () -> executor.execute(request, new ArrayList<>(), com.hmdp.component.AgentLoopObserver.noop(), lease));

        assertEquals(1, modelCalls.get());
        assertEquals(0, toolCalls.get());
    }

    static class TestLoopTools {
        private final AtomicInteger calls;

        TestLoopTools() {
            this(new AtomicInteger());
        }

        TestLoopTools(AtomicInteger calls) {
            this.calls = calls;
        }

        @Tool(description = "回显输入文本")
        public String echo(@ToolParam(description = "输入文本") String text) {
            calls.incrementAndGet();
            return "echo:" + text;
        }
    }

    static class LargeResultTool {
        private final AtomicInteger calls;

        LargeResultTool(AtomicInteger calls) {
            this.calls = calls;
        }

        @Tool(description = "返回较大的测试结果")
        public String large() {
            calls.incrementAndGet();
            return "x".repeat(12_000);
        }
    }
}

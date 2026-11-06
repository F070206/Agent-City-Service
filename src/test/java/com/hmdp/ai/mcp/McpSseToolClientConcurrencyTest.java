package com.hmdp.ai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiAgentProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class McpSseToolClientConcurrencyTest {

    @Test
    void failedCallDoesNotCloseSharedClientWhileAnotherCallIsInFlight() throws Exception {
        CountDownLatch successfulCallStarted = new CountDownLatch(1);
        CountDownLatch allowSuccessfulCallToFinish = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        McpSchema.CallToolResult successfulResponse = McpSchema.CallToolResult.builder()
                .isError(false)
                .content(List.of(new McpSchema.TextContent("{\"status\":\"ok\"}")))
                .build();
        McpSyncClient sharedClient = mock(McpSyncClient.class);
        doAnswer(invocation -> {
            String toolName = invocation.<McpSchema.CallToolRequest>getArgument(0).name();
            if ("fail".equals(toolName)) {
                assertTrue(successfulCallStarted.await(5, TimeUnit.SECONDS));
                throw new IllegalStateException("remote call failed");
            }
            successfulCallStarted.countDown();
            assertTrue(allowSuccessfulCallToFinish.await(5, TimeUnit.SECONDS));
            if (closed.get()) {
                throw new IllegalStateException("shared client was closed by another call");
            }
            return successfulResponse;
        }).when(sharedClient).callTool(any(McpSchema.CallToolRequest.class));
        doAnswer(invocation -> {
            closed.set(true);
            return null;
        }).when(sharedClient).close();

        McpSseToolClient client = new McpSseToolClient(new ObjectMapper());
        @SuppressWarnings("unchecked")
        Map<String, McpSseToolClient.ClientHolder> clients =
                (Map<String, McpSseToolClient.ClientHolder>) ReflectionTestUtils.getField(client, "clients");
        clients.put("route", new McpSseToolClient.ClientHolder(sharedClient));
        AiAgentProperties.McpServer server = new AiAgentProperties.McpServer();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var failedCall = executor.submit(() -> client.call("route", server, "fail", Map.of(), 1000));
            var concurrentCall = executor.submit(() -> client.call("route", server, "success", Map.of(), 1000));

            assertThrows(ExecutionException.class, () -> failedCall.get(5, TimeUnit.SECONDS));
            allowSuccessfulCallToFinish.countDown();

            assertEquals("ok", concurrentCall.get(5, TimeUnit.SECONDS).path("status").asText());
            verify(sharedClient, times(1)).close();
        } finally {
            allowSuccessfulCallToFinish.countDown();
            executor.shutdownNow();
            client.close();
        }
    }
}

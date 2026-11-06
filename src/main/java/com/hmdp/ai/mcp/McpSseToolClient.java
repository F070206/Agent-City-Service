package com.hmdp.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiAgentProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** MCP Java SDK client for the SSE transport supported by this project's Spring AI version. */
@Slf4j
class McpSseToolClient implements AutoCloseable {
    private final ObjectMapper objectMapper;
    private final Map<String, ClientHolder> clients = new ConcurrentHashMap<>();
    private volatile boolean closed;

    McpSseToolClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode call(String serverName, AiAgentProperties.McpServer server, String toolName,
                  Map<String, Object> arguments, int timeoutMillis) throws Exception {
        ClientHolder holder = acquire(serverName, server, timeoutMillis);
        try {
            McpSchema.CallToolResult result = holder.client
                    .callTool(new McpSchema.CallToolRequest(toolName, arguments));
            if (result == null || Boolean.TRUE.equals(result.isError())) {
                throw new IllegalStateException("MCP tool reported an error: " + toolName);
            }
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent text && StringUtils.hasText(text.text())) {
                    return objectMapper.readTree(text.text());
                }
            }
            throw new IllegalStateException("MCP tool returned no JSON text: " + toolName);
        } catch (Exception e) {
            retire(serverName, holder);
            throw e;
        } finally {
            closeIfReady(serverName, holder, holder.release());
        }
    }

    private ClientHolder acquire(String serverName, AiAgentProperties.McpServer server, int timeoutMillis) {
        while (true) {
            if (closed) {
                throw new IllegalStateException("MCP client is closed");
            }
            ClientHolder holder = clients.computeIfAbsent(serverName,
                    ignored -> new ClientHolder(connect(server, timeoutMillis)));
            if (holder.acquire()) {
                if (!closed) {
                    return holder;
                }
                retire(serverName, holder);
                closeIfReady(serverName, holder, holder.release());
                throw new IllegalStateException("MCP client is closed");
            }
            clients.remove(serverName, holder);
        }
    }

    private void retire(String serverName, ClientHolder holder) {
        clients.remove(serverName, holder);
        closeIfReady(serverName, holder, holder.retire());
    }

    private void closeIfReady(String serverName, ClientHolder holder, boolean closeNow) {
        if (!closeNow) {
            return;
        }
        try {
            holder.client.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close MCP client after use, server={}", serverName, e);
        }
    }

    private McpSyncClient connect(AiAgentProperties.McpServer server, int timeoutMillis) {
        Duration timeout = McpTimeouts.boundedDuration(timeoutMillis);
        HttpClientSseClientTransport.Builder transport = HttpClientSseClientTransport
                .builder(server.getBaseUrl())
                .sseEndpoint(server.getInvokePath())
                .customizeClient(builder -> builder.connectTimeout(timeout));
        if (StringUtils.hasText(server.getApiKey())) {
            transport.customizeRequest(builder -> builder.header("Authorization", "Bearer " + server.getApiKey()));
        }
        McpSyncClient client = McpClient.sync(transport.build())
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .build();
        try {
            client.initialize();
            return client;
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    @Override
    public void close() {
        closed = true;
        List<Map.Entry<String, ClientHolder>> activeClients = new ArrayList<>(clients.entrySet());
        for (Map.Entry<String, ClientHolder> entry : activeClients) {
            if (clients.remove(entry.getKey(), entry.getValue())) {
                closeIfReady(entry.getKey(), entry.getValue(), entry.getValue().retire());
            }
        }
    }

    static final class ClientHolder {
        private final McpSyncClient client;
        private int activeCalls;
        private boolean retired;
        private boolean closeScheduled;

        ClientHolder(McpSyncClient client) {
            this.client = client;
        }

        synchronized boolean acquire() {
            if (retired) {
                return false;
            }
            activeCalls++;
            return true;
        }

        synchronized boolean retire() {
            retired = true;
            return scheduleCloseIfUnused();
        }

        synchronized boolean release() {
            activeCalls--;
            return scheduleCloseIfUnused();
        }

        private boolean scheduleCloseIfUnused() {
            if (!retired || activeCalls != 0 || closeScheduled) {
                return false;
            }
            closeScheduled = true;
            return true;
        }
    }
}

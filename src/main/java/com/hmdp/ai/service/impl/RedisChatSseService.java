package com.hmdp.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.ChatStreamEvent;
import com.hmdp.service.ChatSseService;
import com.hmdp.service.impl.ChatSseServiceImpl;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@Primary
@Profile("micro-ai")
@RequiredArgsConstructor
public class RedisChatSseService implements ChatSseService, MessageListener {
    public static final String CHANNEL = "cityai:chat:sse";

    private final ChatSseServiceImpl local;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String instanceId = UUID.randomUUID().toString();

    @Override
    public SseEmitter connect(Long userId, Long conversationId) {
        return local.connect(userId, conversationId);
    }

    @Override
    public void send(Long userId, Long conversationId, ChatStreamEvent event) {
        local.send(userId, conversationId, event);
        try {
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(
                    new Envelope(instanceId, userId, conversationId, event)));
        } catch (Exception e) {
            // 本机已投递；其他实例上的客户端仍可通过已有的消息轮询取回最终回复。
            log.error("跨实例 SSE 事件投递失败，conversationId={}, type={}",
                    conversationId, event.getType(), e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            Envelope envelope = objectMapper.readValue(message.getBody(), Envelope.class);
            if (instanceId.equals(envelope.origin())) {
                return;
            }
            if (envelope.userId() == null || envelope.conversationId() == null
                    || envelope.event() == null || envelope.event().getType() == null) {
                throw new IllegalArgumentException("Invalid SSE event envelope");
            }
            local.send(envelope.userId(), envelope.conversationId(), envelope.event());
        } catch (IOException | IllegalArgumentException e) {
            log.error("跨实例 SSE 事件解析失败", e);
        }
    }

    public record Envelope(String origin, Long userId, Long conversationId, ChatStreamEvent event) {
    }
}

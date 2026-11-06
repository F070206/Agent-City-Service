package com.hmdp.service.impl;

import com.hmdp.dto.ChatStreamEvent;
import com.hmdp.service.ChatSseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class ChatSseServiceImpl implements ChatSseService {

    private static final long SSE_TIMEOUT_MILLIS = 120_000L;

    @Value("${ai.sse.max-connections-per-user:8}")
    private int maxConnectionsPerUser = 8;

    private final ConcurrentHashMap<StreamKey, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, java.util.Set<SseEmitter>> userEmitters = new ConcurrentHashMap<>();

    @Override
    public SseEmitter connect(Long userId, Long conversationId) {
        StreamKey key = new StreamKey(userId, conversationId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        registerUserEmitter(userId, emitter);
        emitters.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(key, emitter));
        emitter.onTimeout(() -> removeEmitter(key, emitter));
        emitter.onError(ex -> removeEmitter(key, emitter));
        send(userId, conversationId, ChatStreamEvent.builder()
                .type("connected")
                .conversationId(conversationId)
                .payload(Map.of("conversationId", conversationId))
                .createdTime(LocalDateTime.now())
                .build());
        return emitter;
    }

    @Override
    public void send(Long userId, Long conversationId, ChatStreamEvent event) {
        List<SseEmitter> emitterList = emitters.get(new StreamKey(userId, conversationId));
        if (emitterList == null || emitterList.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitterList) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getType())
                        .data(event));
            }
            catch (IOException | IllegalStateException e) {
                log.debug("Failed to send SSE event, conversationId={}, type={}", conversationId, event.getType(), e);
                removeEmitter(new StreamKey(userId, conversationId), emitter);
            }
        }
    }

    private void removeEmitter(StreamKey key, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitterList = emitters.get(key);
        boolean removed = emitterList != null && emitterList.remove(emitter);
        if (emitterList != null && emitterList.isEmpty()) {
            emitters.remove(key, emitterList);
        }
        if (removed) {
            userEmitters.computeIfPresent(key.userId(), (userId, connections) -> {
                connections.remove(emitter);
                return connections.isEmpty() ? null : connections;
            });
        }
    }

    private void registerUserEmitter(Long userId, SseEmitter emitter) {
        userEmitters.compute(userId, (ignored, connections) -> {
            java.util.Set<SseEmitter> current = connections == null
                    ? ConcurrentHashMap.newKeySet() : connections;
            if (current.size() >= Math.max(maxConnectionsPerUser, 1)) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "当前账号的 AI 流式连接数已达上限");
            }
            current.add(emitter);
            return current;
        });
    }

    private record StreamKey(Long userId, Long conversationId) {
    }
}

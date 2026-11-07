package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hmdp.ai.service.impl.RedisChatSseService;
import com.hmdp.dto.ChatStreamEvent;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChatSseServiceImplTest {
    @Test
    void limitsConcurrentStreamsPerUserAndReleasesCapacityAfterCompletion() {
        ChatSseServiceImpl service = new ChatSseServiceImpl();
        java.util.List<SseEmitter> emitters = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            emitters.add(service.connect(7L, (long) i));
        }

        ResponseStatusException limited = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class, () -> service.connect(7L, 99L));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, limited.getStatusCode());

        emitters.get(0).complete();
        service.send(7L, 0L, ChatStreamEvent.builder()
                .type("done").conversationId(0L).payload(Map.of("status", "finished"))
                .createdTime(LocalDateTime.now()).build());
        assertTrue(service.connect(7L, 99L) != null);
    }

    @Test
    void streamLimitIsIsolatedPerUser() {
        ChatSseServiceImpl service = new ChatSseServiceImpl();
        for (int i = 0; i < 8; i++) {
            service.connect(7L, (long) i);
        }

        assertTrue(service.connect(8L, 1L) != null);
    }

    @Test
    void streamConnectionHasFiniteLifetimeLongerThanClientWaitWindow() {
        ChatSseServiceImpl service = new ChatSseServiceImpl();

        SseEmitter emitter = service.connect(7L, 11L);

        assertEquals(120_000L, ((Number) ReflectionTestUtils.getField(emitter, "timeout")).longValue());
    }

    @Test
    void completedEmitterDoesNotInterruptDeliveryToOtherConnections() {
        ChatSseServiceImpl local = new ChatSseServiceImpl();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisChatSseService service = new RedisChatSseService(local, redis,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        SseEmitter completed = service.connect(7L, 11L);
        SseEmitter active = service.connect(7L, 11L);
        completed.complete();

        assertDoesNotThrow(() -> service.send(7L, 11L, ChatStreamEvent.builder()
                .type("answer_delta")
                .conversationId(11L)
                .payload(Map.of("delta", "hello"))
                .createdTime(LocalDateTime.now())
                .build()));

        @SuppressWarnings("unchecked")
        Map<Object, CopyOnWriteArrayList<SseEmitter>> emitters =
                (Map<Object, CopyOnWriteArrayList<SseEmitter>>) ReflectionTestUtils.getField(local, "emitters");
        CopyOnWriteArrayList<SseEmitter> remaining = emitters.values().iterator().next();
        assertFalse(remaining.contains(completed));
        assertTrue(remaining.contains(active));
        @SuppressWarnings("unchecked")
        Set<Object> queuedEvents = (Set<Object>) ReflectionTestUtils.getField(active, "earlySendAttempts");
        assertTrue(queuedEvents.size() > 1);
        verify(redis).convertAndSend(eq(RedisChatSseService.CHANNEL), anyString());
    }

    @Test
    void reconnectDuringLastEmitterRemovalKeepsNewConnectionReachable() throws Exception {
        ChatSseServiceImpl service = new ChatSseServiceImpl();
        BlockingRemoveEmitterMap emitters = new BlockingRemoveEmitterMap();
        ReflectionTestUtils.setField(service, "emitters", emitters);
        SseEmitter first = service.connect(7L, 11L);

        Thread completion = new Thread(() -> {
            first.complete();
            service.send(7L, 11L, ChatStreamEvent.builder()
                    .type("done").conversationId(11L).payload(Map.of("status", "finished"))
                    .createdTime(LocalDateTime.now()).build());
        });
        completion.start();
        emitters.awaitRemoval();
        SseEmitter reconnected = service.connect(7L, 11L);
        emitters.resumeRemoval();
        completion.join(2_000);

        assertFalse(completion.isAlive(), "Emitter completion should finish");
        assertTrue(emitters.values().stream().anyMatch(list -> list.contains(reconnected)),
                "The reconnected stream must remain reachable after the old stream is removed");
        reconnected.complete();
    }

    private static final class BlockingRemoveEmitterMap
            extends ConcurrentHashMap<Object, CopyOnWriteArrayList<SseEmitter>> {
        private final CountDownLatch removalEntered = new CountDownLatch(1);
        private final CountDownLatch allowRemoval = new CountDownLatch(1);

        @Override
        public boolean remove(Object key, Object value) {
            removalEntered.countDown();
            try {
                if (!allowRemoval.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to resume emitter removal");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while pausing emitter removal", e);
            }
            return super.remove(key, value);
        }

        private void awaitRemoval() throws InterruptedException {
            removalEntered.await(1, TimeUnit.SECONDS);
        }

        private void resumeRemoval() {
            allowRemoval.countDown();
        }
    }
}

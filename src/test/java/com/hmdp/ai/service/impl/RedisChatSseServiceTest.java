package com.hmdp.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hmdp.dto.ChatStreamEvent;
import com.hmdp.service.impl.ChatSseServiceImpl;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CACHE_TEST_REDIS_HOST", matches = ".+")
class RedisChatSseServiceTest {

    private LettuceConnectionFactory connection;
    private RedisMessageListenerContainer firstContainer;
    private RedisMessageListenerContainer secondContainer;

    @BeforeEach
    void setUp() {
        connection = new LettuceConnectionFactory(System.getenv("CACHE_TEST_REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("CACHE_TEST_REDIS_PORT", "6379")));
        connection.afterPropertiesSet();
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (firstContainer != null) {
            firstContainer.stop();
            firstContainer.destroy();
        }
        if (secondContainer != null) {
            secondContainer.stop();
            secondContainer.destroy();
        }
        if (connection != null) {
            connection.destroy();
        }
    }

    @Test
    void eventFromSecondInstanceReachesFirstInstanceStream() throws Exception {
        StringRedisTemplate redis = new StringRedisTemplate(connection);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RecordingLocal firstLocal = new RecordingLocal();
        RecordingLocal secondLocal = new RecordingLocal();
        RedisChatSseService first = new RedisChatSseService(firstLocal, redis, mapper);
        RedisChatSseService second = new RedisChatSseService(secondLocal, redis, mapper);
        firstContainer = subscribe(first);
        secondContainer = subscribe(second);
        Long conversationId = Math.abs(java.util.UUID.randomUUID().getMostSignificantBits());
        first.connect(9L, conversationId);
        ChatStreamEvent event = ChatStreamEvent.builder().type("answer_delta")
                .conversationId(conversationId).payload(Map.of("delta", "hello"))
                .createdTime(LocalDateTime.now()).build();

        int sent = 0;
        for (int attempt = 0; attempt < 20 && firstLocal.count("answer_delta") == 0; attempt++) {
            second.send(9L, conversationId, event);
            sent++;
            Thread.sleep(50);
        }

        assertTrue(firstLocal.count("answer_delta") > 0);
        assertEquals(sent, secondLocal.count("answer_delta"));
        assertEquals(0, secondLocal.count("connected"));
        assertEquals(1, firstLocal.count("connected"));
        assertEquals("hello", ((Map<?, ?>) firstLocal.last("answer_delta").getPayload()).get("delta"));
    }

    private RedisMessageListenerContainer subscribe(RedisChatSseService service) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connection);
        container.addMessageListener(service, new ChannelTopic(RedisChatSseService.CHANNEL));
        container.afterPropertiesSet();
        container.start();
        return container;
    }

    private static class RecordingLocal extends ChatSseServiceImpl {
        private final CopyOnWriteArrayList<ChatStreamEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void send(Long userId, Long conversationId, ChatStreamEvent event) {
            events.add(event);
            super.send(userId, conversationId, event);
        }

        long count(String type) {
            return events.stream().filter(event -> type.equals(event.getType())).count();
        }

        ChatStreamEvent last(String type) {
            return events.stream().filter(event -> type.equals(event.getType())).reduce((a, b) -> b).orElseThrow();
        }
    }
}

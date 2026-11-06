package com.hmdp.ai.config;

import com.hmdp.ai.service.impl.RedisChatSseService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@Profile("micro-ai")
public class ChatSseRedisConfiguration {

    @Bean
    public RedisMessageListenerContainer chatSseMessageListenerContainer(
            RedisConnectionFactory connectionFactory, RedisChatSseService listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(RedisChatSseService.CHANNEL));
        return container;
    }
}

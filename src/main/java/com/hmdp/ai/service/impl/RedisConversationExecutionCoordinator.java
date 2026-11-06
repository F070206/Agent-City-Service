package com.hmdp.ai.service.impl;

import com.hmdp.component.ConversationExecutionCoordinator;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Primary
@Profile("micro-ai")
@RequiredArgsConstructor
public class RedisConversationExecutionCoordinator extends ConversationExecutionCoordinator {
    private final RedissonClient redissonClient;

    @Override
    public void runSerial(Long conversationId, Runnable task) {
        RLock lock = redissonClient.getFairLock("lock:ai:conversation:" + conversationId);
        lock.lock();
        try {
            task.run();
        } finally {
            lock.unlock();
        }
    }
}

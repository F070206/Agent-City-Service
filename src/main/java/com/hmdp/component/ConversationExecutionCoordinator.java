package com.hmdp.component;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class ConversationExecutionCoordinator {

    private final ConcurrentMap<Long, LockState> locks = new ConcurrentHashMap<>();

    public void runSerial(Long conversationId, Runnable task) {
        LockState state = locks.compute(conversationId, (ignored, current) -> {
            LockState selected = current == null ? new LockState() : current;
            selected.users++;
            return selected;
        });
        state.lock.lock();
        try {
            task.run();
        }
        finally {
            state.lock.unlock();
            locks.computeIfPresent(conversationId, (ignored, current) -> {
                current.users--;
                return current.users == 0 ? null : current;
            });
        }
    }

    private static final class LockState {
        private final ReentrantLock lock = new ReentrantLock(true);
        private int users;
    }
}

package com.hmdp.ai.component;

import com.hmdp.ai.config.AiAgentProperties;
import com.hmdp.ai.rag.indexer.AiVectorIndexService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class KnowledgeBaseInitializerTest {
    private static final String LOCK_NAME = "lock:ai:vector-index";

    @Test
    void sharedVectorStoreBootstrapRunsUnderRedisLock() {
        AiVectorIndexService indexes = mock(AiVectorIndexService.class);
        RedissonClient redis = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redis.getLock(LOCK_NAME)).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        AiAgentProperties properties = new AiAgentProperties();

        new KnowledgeBaseInitializer(indexes, properties, redis).init();

        verify(lock).lock();
        verify(indexes).ensureInitialized();
        verify(lock).unlock();
    }

    @Test
    void concurrentInstancesDoNotInitializeSharedVectorStoreAtTheSameTime() throws Exception {
        AiVectorIndexService indexes = mock(AiVectorIndexService.class);
        RedissonClient redis = mock(RedissonClient.class);
        CountDownLatch secondLockAttempted = new CountDownLatch(1);
        RLock lock = localLock(secondLockAttempted);
        when(redis.getLock(LOCK_NAME)).thenReturn(lock);
        AiAgentProperties properties = new AiAgentProperties();
        KnowledgeBaseInitializer first = new KnowledgeBaseInitializer(indexes, properties, redis);
        KnowledgeBaseInitializer second = new KnowledgeBaseInitializer(indexes, properties, redis);
        CountDownLatch firstInitializationStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstInitialization = new CountDownLatch(1);
        AtomicInteger activeInitializations = new AtomicInteger();
        AtomicInteger maxActiveInitializations = new AtomicInteger();
        AtomicInteger invocationCount = new AtomicInteger();
        doAnswer(invocation -> {
            int active = activeInitializations.incrementAndGet();
            maxActiveInitializations.accumulateAndGet(active, Math::max);
            if (invocationCount.incrementAndGet() == 1) {
                firstInitializationStarted.countDown();
                if (!releaseFirstInitialization.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("first initialization was not released");
                }
            }
            activeInitializations.decrementAndGet();
            return null;
        }).when(indexes).ensureInitialized();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            executor.submit(() -> runInitializer(first, failure));
            assertTrue(firstInitializationStarted.await(2, TimeUnit.SECONDS));
            executor.submit(() -> {
                Thread.currentThread().setName("ai-bootstrap-second");
                runInitializer(second, failure);
            });
            assertTrue(secondLockAttempted.await(2, TimeUnit.SECONDS));
            releaseFirstInitialization.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            releaseFirstInitialization.countDown();
            executor.shutdownNow();
        }

        assertNull(failure.get());
        assertEquals(2, invocationCount.get());
        assertEquals(1, maxActiveInitializations.get());
    }

    @Test
    void localVectorStoresDoNotAcquireRedisBootstrapLock() {
        AiVectorIndexService indexes = mock(AiVectorIndexService.class);
        RedissonClient redis = mock(RedissonClient.class);
        AiAgentProperties properties = new AiAgentProperties();
        properties.getRag().setVectorStoreEnabled(false);

        new KnowledgeBaseInitializer(indexes, properties, redis).init();

        verify(indexes).ensureInitialized();
        verifyNoInteractions(redis);
    }

    @Test
    void bootstrapFailureStillReleasesLockAndCanRemainFailFast() {
        AiVectorIndexService indexes = mock(AiVectorIndexService.class);
        RedissonClient redis = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redis.getLock(LOCK_NAME)).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doThrow(new IllegalStateException("index failed")).when(indexes).ensureInitialized();
        AiAgentProperties properties = new AiAgentProperties();
        properties.getBootstrap().setFailFast(true);

        assertThrows(IllegalStateException.class,
                () -> new KnowledgeBaseInitializer(indexes, properties, redis).init());

        verify(lock).unlock();
    }

    private RLock localLock(CountDownLatch secondLockAttempted) {
        ReentrantLock local = new ReentrantLock(true);
        RLock lock = mock(RLock.class);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("ai-bootstrap-second")) {
                secondLockAttempted.countDown();
            }
            local.lock();
            return null;
        }).when(lock).lock();
        when(lock.isHeldByCurrentThread()).thenAnswer(invocation -> local.isHeldByCurrentThread());
        doAnswer(invocation -> {
            local.unlock();
            return null;
        }).when(lock).unlock();
        return lock;
    }

    private void runInitializer(KnowledgeBaseInitializer initializer, AtomicReference<Throwable> failure) {
        try {
            initializer.init();
        } catch (Throwable e) {
            failure.compareAndSet(null, e);
        }
    }
}

package com.hmdp.component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class ConversationExecutionCoordinatorTest {

    @Test
    void keepsSameConversationLockWhileAnotherTaskIsUsingIt() throws Exception {
        ConversationExecutionCoordinator coordinator = new ConversationExecutionCoordinator();
        CountDownLatch firstCleanup = new CountDownLatch(1);
        CountDownLatch allowFirstCleanup = new CountDownLatch(1);
        AtomicBoolean pauseOnce = new AtomicBoolean(true);
        ConcurrentHashMap<Long, Object> states = new ConcurrentHashMap<>() {
            @Override
            public Object computeIfPresent(Long key,
                    BiFunction<? super Long, ? super Object, ? extends Object> remappingFunction) {
                if (pauseOnce.compareAndSet(true, false)) {
                    firstCleanup.countDown();
                    try {
                        if (!allowFirstCleanup.await(2, TimeUnit.SECONDS)) {
                            throw new AssertionError("first cleanup did not resume");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
                return super.computeIfPresent(key, remappingFunction);
            }
        };
        ReflectionTestUtils.setField(coordinator, "locks", states);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        CountDownLatch thirdEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> first = executor.submit(() -> coordinator.runSerial(7L, () -> {}));
            assertTrue(firstCleanup.await(2, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> coordinator.runSerial(7L, () -> {
                secondEntered.countDown();
                try {
                    if (!releaseSecond.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("second task did not resume");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }));
            assertTrue(secondEntered.await(2, TimeUnit.SECONDS));
            allowFirstCleanup.countDown();
            first.get(2, TimeUnit.SECONDS);

            Future<?> third = executor.submit(() -> coordinator.runSerial(7L, thirdEntered::countDown));
            assertFalse(thirdEntered.await(100, TimeUnit.MILLISECONDS));
            releaseSecond.countDown();
            second.get(2, TimeUnit.SECONDS);
            third.get(2, TimeUnit.SECONDS);
            assertTrue(thirdEntered.await(1, TimeUnit.SECONDS));
            assertTrue(states.isEmpty());
        } finally {
            allowFirstCleanup.countDown();
            releaseSecond.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void queuedTasksAcquireConversationLockInFifoOrder() throws Exception {
        ConversationExecutionCoordinator coordinator = new ConversationExecutionCoordinator();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Integer> order = new java.util.ArrayList<>();
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        AtomicReference<Thread> thirdThread = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> first = executor.submit(() -> coordinator.runSerial(9L, () -> {
                firstEntered.countDown();
                try {
                    if (!releaseFirst.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("first task did not resume");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }));
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

            @SuppressWarnings("unchecked")
            ConcurrentHashMap<Long, Object> locks = (ConcurrentHashMap<Long, Object>)
                    ReflectionTestUtils.getField(coordinator, "locks");
            ReentrantLock lock = (ReentrantLock) ReflectionTestUtils.getField(locks.get(9L), "lock");
            assertTrue(lock.isFair());

            Future<?> second = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                coordinator.runSerial(9L, () -> order.add(2));
            });
            awaitQueued(lock, secondThread);
            Future<?> third = executor.submit(() -> {
                thirdThread.set(Thread.currentThread());
                coordinator.runSerial(9L, () -> order.add(3));
            });
            awaitQueued(lock, thirdThread);

            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            third.get(2, TimeUnit.SECONDS);
            assertEquals(java.util.List.of(2, 3), order);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private void awaitQueued(ReentrantLock lock, AtomicReference<Thread> thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.get() == null || !lock.hasQueuedThread(thread.get())) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            Thread.sleep(1);
        }
        assertTrue(thread.get() != null && lock.hasQueuedThread(thread.get()),
                "task did not reach the conversation lock queue");
    }
}

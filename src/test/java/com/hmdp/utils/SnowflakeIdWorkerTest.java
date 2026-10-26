package com.hmdp.utils;

import com.baomidou.mybatisplus.core.exceptions.MybatisPlusException;
import com.baomidou.mybatisplus.core.toolkit.Sequence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeIdWorkerTest {

    private static final long TIMESTAMP = 1700000000000L;

    @Test
    void shouldUseMillisecondTimestampAndConfiguredNodeBits() {
        AtomicLong clock = new AtomicLong(TIMESTAMP);
        SnowflakeIdWorker worker = workerWithClock(5, 9, clock::get);

        long first = worker.nextId();
        long second = worker.nextId();
        clock.incrementAndGet();
        long third = worker.nextId();

        assertTrue(first > 0);
        assertEquals(TIMESTAMP, Sequence.parseIdTimestamp(first));
        assertEquals(5, (first >>> 12) & 31);
        assertEquals(9, (first >>> 17) & 31);
        assertEquals(first + 1, second);
        assertEquals(TIMESTAMP + 1, Sequence.parseIdTimestamp(third));
        assertTrue(third > second);
    }

    @Test
    void differentNodesShouldNotCollideInTheSameMillisecond() {
        List<SnowflakeIdWorker> workers = List.of(
                workerWithClock(0, 0, () -> TIMESTAMP),
                workerWithClock(1, 0, () -> TIMESTAMP),
                workerWithClock(0, 1, () -> TIMESTAMP),
                workerWithClock(31, 31, () -> TIMESTAMP)
        );
        Set<Long> ids = new HashSet<>();
        for (SnowflakeIdWorker worker : workers) {
            for (int i = 0; i < 1000; i++) {
                assertTrue(ids.add(worker.nextId()), "Different nodes must not issue duplicate IDs");
            }
        }
        assertEquals(4000, ids.size());
    }

    @Test
    @Timeout(15)
    void shouldGenerateUniqueIdsUnderConcurrentRequests() throws Exception {
        SnowflakeIdWorker worker = new SnowflakeIdWorker(3, 2);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Long>>> futures = new ArrayList<>();
        try {
            for (int thread = 0; thread < 8; thread++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    List<Long> ids = new ArrayList<>();
                    for (int i = 0; i < 2000; i++) {
                        ids.add(worker.nextId());
                    }
                    return ids;
                }));
            }
            start.countDown();
            Set<Long> uniqueIds = new HashSet<>();
            for (Future<List<Long>> future : futures) {
                uniqueIds.addAll(future.get(10, TimeUnit.SECONDS));
            }
            assertEquals(16000, uniqueIds.size());
        }
        finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(5)
    void shouldAdvanceToNextMillisecondWhenSequenceIsExhausted() {
        AtomicLong timeReads = new AtomicLong();
        SnowflakeIdWorker worker = workerWithClock(1, 1,
                () -> TIMESTAMP + timeReads.getAndIncrement() / 4096);
        Set<Long> ids = new HashSet<>();

        long lastId = 0;
        for (int i = 0; i < 5000; i++) {
            lastId = worker.nextId();
            assertTrue(ids.add(lastId), "Sequence rollover must not reuse an ID");
        }
        assertTrue(Sequence.parseIdTimestamp(lastId) > TIMESTAMP);
    }

    @Test
    void shouldRecoverAfterSmallClockRollback() {
        AtomicLong timeReads = new AtomicLong();
        SnowflakeIdWorker worker = workerWithClock(1, 1,
                () -> timeReads.getAndIncrement() == 1 ? TIMESTAMP - 1 : TIMESTAMP);
        long first = worker.nextId();
        assertTrue(worker.nextId() > first);
    }

    @Test
    void shouldPropagateLargeClockRollbackInsteadOfIssuingAnId() {
        AtomicLong clock = new AtomicLong(TIMESTAMP);
        SnowflakeIdWorker worker = workerWithClock(1, 1, clock::get);
        worker.nextId();
        clock.addAndGet(-10);
        RuntimeException exception = assertThrows(RuntimeException.class, worker::nextId);
        assertTrue(exception.getMessage().contains("Clock moved backwards"));
    }

    @ParameterizedTest
    @CsvSource({"-1, 0", "32, 0", "0, -1", "0, 32"})
    void shouldRejectInvalidNodeConfiguration(long workerId, long datacenterId) {
        assertThrows(MybatisPlusException.class, () -> new SnowflakeIdWorker(workerId, datacenterId));
    }

    @Test
    void springShouldCreateConfiguredGeneratorWithoutRedis() {
        new ApplicationContextRunner()
                .withUserConfiguration(SnowflakeIdWorker.class)
                .withPropertyValues("id.snowflake.worker-id=7", "id.snowflake.datacenter-id=12")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    long id = context.getBean(SnowflakeIdWorker.class).nextId();
                    assertEquals(7, (id >>> 12) & 31);
                    assertEquals(12, (id >>> 17) & 31);
                });
    }

    private SnowflakeIdWorker workerWithClock(long workerId, long datacenterId, LongSupplier clock) {
        SnowflakeIdWorker worker = new SnowflakeIdWorker(workerId, datacenterId);
        // 仅替换时间来源，仍运行依赖中的真实发号算法，不修改项目的 Mockito 配置。
        Sequence sequence = new Sequence(workerId, datacenterId) {
            @Override
            protected long timeGen() {
                return clock.getAsLong();
            }
        };
        ReflectionTestUtils.setField(worker, "sequence", sequence);
        return worker;
    }
}

package com.hmdp.ai.service.impl;

import com.hmdp.ai.mapper.AiTaskMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiTaskLeaseKeeperTest {
    @Test
    void scheduledHeartbeatRenewsOnlyTheClaimedTask() {
        AiTaskMapper tasks = mock(AiTaskMapper.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
        when(tasks.renewLease(42L, "claim-42")).thenReturn(1);
        AiTaskLeaseKeeper keeper = new AiTaskLeaseKeeper(tasks, scheduler);

        AiTaskLease lease = keeper.start(42L, "claim-42");
        assertTrue(lease.isActive());
        ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(heartbeat.capture(), any(Instant.class),
                eq(Duration.ofMinutes(1)));
        verifyNoInteractions(tasks);

        heartbeat.getValue().run();
        verify(tasks).renewLease(42L, "claim-42");
        assertTrue(lease.isActive());
    }

    @Test
    void lostLeaseStopsFurtherRenewalAndMarksHandleInactive() {
        AiTaskMapper tasks = mock(AiTaskMapper.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
        when(tasks.renewLease(42L, "claim-42")).thenReturn(0);
        AiTaskLeaseKeeper keeper = new AiTaskLeaseKeeper(tasks, scheduler);
        AiTaskLease lease = keeper.start(42L, "claim-42");
        ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(heartbeat.capture(), any(Instant.class), any(Duration.class));

        heartbeat.getValue().run();

        assertFalse(lease.isActive());
        assertThrows(AiTaskLeaseLostException.class, lease::assertActive);
        verify(future).cancel(false);
    }
}

package com.hmdp.ai.service.impl;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class AiTaskLease {
    private final Long messageId;
    private final AtomicBoolean lost = new AtomicBoolean();
    private volatile ScheduledFuture<?> renewal;

    AiTaskLease(Long messageId) {
        this.messageId = messageId;
    }

    void setRenewal(ScheduledFuture<?> renewal) {
        this.renewal = renewal;
        if (lost.get()) {
            renewal.cancel(false);
        }
    }

    void markLost() {
        if (lost.compareAndSet(false, true)) {
            ScheduledFuture<?> current = renewal;
            if (current != null) {
                current.cancel(false);
            }
        }
    }

    public boolean isActive() {
        return !lost.get();
    }

    public void assertActive() {
        if (lost.get()) {
            throw new AiTaskLeaseLostException(messageId);
        }
    }

    public void cancelRenewal() {
        ScheduledFuture<?> current = renewal;
        if (current != null) {
            current.cancel(false);
        }
    }
}

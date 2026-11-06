package com.hmdp.entity;

import lombok.Data;

@Data
public class OrderStockSyncBacklog {
    private long pendingCount;
    private long oldestPendingAgeSeconds;
    private long repeatedFailureCount;
}

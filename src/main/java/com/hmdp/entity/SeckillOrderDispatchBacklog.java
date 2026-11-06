package com.hmdp.entity;

import lombok.Data;

@Data
public class SeckillOrderDispatchBacklog {
    private long orderPendingCount;
    private long orderOldestPendingAgeSeconds;
    private long orderRepeatedFailureCount;
    private long timeoutPendingCount;
    private long timeoutOldestPendingAgeSeconds;
    private long timeoutRepeatedFailureCount;
}

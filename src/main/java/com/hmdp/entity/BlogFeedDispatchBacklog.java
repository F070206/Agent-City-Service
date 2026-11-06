package com.hmdp.entity;

import lombok.Data;

@Data
public class BlogFeedDispatchBacklog {
    private Long pendingCount;
    private Long oldestPendingAgeSeconds;
    private Long repeatedFailureCount;
}

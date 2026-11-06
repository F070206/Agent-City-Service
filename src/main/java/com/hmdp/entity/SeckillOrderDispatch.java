package com.hmdp.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SeckillOrderDispatch {
    private Long orderId;
    private String payload;
    private Long userId;
    private LocalDateTime timeoutRetryTime;
    private Integer completed;
}

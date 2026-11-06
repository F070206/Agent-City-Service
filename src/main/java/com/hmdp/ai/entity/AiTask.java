package com.hmdp.ai.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiTask {
    private Long messageId;
    private Long conversationId;
    private Long userId;
    private String content;
    private Double x;
    private Double y;
    private Integer status;
    private LocalDateTime leaseUntil;
    private Integer attempts;
}

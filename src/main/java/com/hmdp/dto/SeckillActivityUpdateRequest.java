package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SeckillActivityUpdateRequest {
    private Integer status;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
}

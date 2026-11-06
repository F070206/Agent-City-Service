package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    private Long expireAtEpochMillis;
    // Retained during the rolling migration so older instances can still read new cache envelopes.
    private LocalDateTime expireTime;
    private Object data;
}

package com.hmdp.dto;

import java.time.LocalDateTime;

public record OrderHistoryItem(
        String orderId,
        Long voucherId,
        Long shopId,
        String voucherTitle,
        Long payValue,
        Integer voucherType,
        Integer status,
        Integer payType,
        LocalDateTime createdTime,
        LocalDateTime updateTime) {
}

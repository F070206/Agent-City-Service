package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** Activity and voucher state loaded with one database round trip for seckill admission. */
@Data
public class SeckillVoucherLookup {
    private Long voucherId;
    private Integer stock;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private Integer voucherStatus;
    private Integer voucherType;
    private Long version;
}

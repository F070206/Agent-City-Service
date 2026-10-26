package com.hmdp.entity;

import lombok.Data;

/** 关单事务提交后待执行的 Redis 库存回补。 */
@Data
public class OrderStockSync {

    private Long orderId;
    private Long voucherId;
    private Long userId;
}

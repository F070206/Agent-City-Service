package com.hmdp.mapper;

import com.hmdp.micro.MapperRole;

import com.hmdp.entity.OrderStockSync;
import com.hmdp.entity.OrderStockSyncBacklog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@MapperRole.Order
public interface OrderStockSyncMapper {

    @Select("SELECT EXISTS(SELECT 1 FROM tb_order_stock_sync WHERE voucher_id = #{voucherId} AND completed = 0)")
    boolean hasPendingForVoucher(@Param("voucherId") Long voucherId);

    @Select("SELECT EXISTS(SELECT 1 FROM tb_order_stock_sync "
            + "WHERE user_id = #{userId} AND voucher_id = #{voucherId} AND order_id <> #{orderId} "
            + "AND completed = 0)")
    boolean hasOtherPendingForUserVoucher(@Param("orderId") Long orderId, @Param("userId") Long userId,
            @Param("voucherId") Long voucherId);

    @Select("SELECT COUNT(*) AS pendingCount, "
            + "COALESCE(TIMESTAMPDIFF(SECOND, MIN(create_time), CURRENT_TIMESTAMP), 0) "
            + "AS oldestPendingAgeSeconds, "
            + "SUM(CASE WHEN attempts >= #{failureThreshold} THEN 1 ELSE 0 END) AS repeatedFailureCount "
            + "FROM tb_order_stock_sync WHERE completed = 0")
    OrderStockSyncBacklog getBacklogSnapshot(@Param("failureThreshold") int failureThreshold);

    @Insert("""
            INSERT INTO tb_order_stock_sync (order_id, voucher_id, user_id)
            VALUES (#{orderId}, #{voucherId}, #{userId})
            """)
    int insertTask(@Param("orderId") Long orderId, @Param("voucherId") Long voucherId,
                   @Param("userId") Long userId);

    @Select("""
            SELECT order_id, voucher_id, user_id FROM tb_order_stock_sync
            WHERE completed = 0 AND next_retry_time <= CURRENT_TIMESTAMP
              AND (claim_until IS NULL OR claim_until <= CURRENT_TIMESTAMP)
            ORDER BY next_retry_time, order_id LIMIT 100
            """)
    List<OrderStockSync> findPending();

    @Update("""
            UPDATE tb_order_stock_sync SET claim_token = #{claimToken},
                claim_until = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 10 MINUTE),
                update_time = CURRENT_TIMESTAMP
            WHERE completed = 0 AND next_retry_time <= CURRENT_TIMESTAMP
              AND (claim_until IS NULL OR claim_until <= CURRENT_TIMESTAMP)
            ORDER BY next_retry_time, order_id LIMIT 100
            """)
    int claimPending(@Param("claimToken") String claimToken);

    @Select("""
            SELECT order_id, voucher_id, user_id FROM tb_order_stock_sync
            WHERE completed = 0 AND claim_token = #{claimToken}
            ORDER BY next_retry_time, order_id
            """)
    List<OrderStockSync> findClaimed(@Param("claimToken") String claimToken);

    @Update("""
            UPDATE tb_order_stock_sync SET completed = 1, claim_token = NULL,
                claim_until = NULL, update_time = CURRENT_TIMESTAMP
            WHERE order_id = #{orderId} AND completed = 0 AND claim_token = #{claimToken}
            """)
    int markCompleted(@Param("orderId") Long orderId, @Param("claimToken") String claimToken);

    @Update("""
            UPDATE tb_order_stock_sync
            SET attempts = attempts + 1, next_retry_time = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 30 SECOND),
                claim_token = NULL, claim_until = NULL, update_time = CURRENT_TIMESTAMP
            WHERE order_id = #{orderId} AND completed = 0 AND claim_token = #{claimToken}
            """)
    int recordFailure(@Param("orderId") Long orderId, @Param("claimToken") String claimToken);
}

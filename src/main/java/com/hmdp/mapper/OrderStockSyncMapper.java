package com.hmdp.mapper;

import com.hmdp.entity.OrderStockSync;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface OrderStockSyncMapper {

    @Insert("""
            INSERT INTO tb_order_stock_sync (order_id, voucher_id, user_id)
            VALUES (#{orderId}, #{voucherId}, #{userId})
            """)
    int insertTask(@Param("orderId") Long orderId, @Param("voucherId") Long voucherId,
                   @Param("userId") Long userId);

    @Select("""
            SELECT order_id, voucher_id, user_id FROM tb_order_stock_sync
            WHERE completed = 0 AND next_retry_time <= CURRENT_TIMESTAMP
            ORDER BY next_retry_time, order_id LIMIT 100
            """)
    List<OrderStockSync> findPending();

    @Update("""
            UPDATE tb_order_stock_sync SET completed = 1, update_time = CURRENT_TIMESTAMP
            WHERE order_id = #{orderId} AND completed = 0
            """)
    int markCompleted(@Param("orderId") Long orderId);

    @Update("""
            UPDATE tb_order_stock_sync
            SET attempts = attempts + 1, next_retry_time = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 30 SECOND),
                update_time = CURRENT_TIMESTAMP
            WHERE order_id = #{orderId} AND completed = 0
            """)
    int recordFailure(@Param("orderId") Long orderId);
}

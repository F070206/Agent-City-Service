package com.hmdp.mapper;

import com.hmdp.entity.OrderTimeoutRetry;
import com.hmdp.micro.MapperRole;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

@MapperRole.Order
public interface OrderTimeoutRetryMapper {

    @Insert("INSERT INTO tb_order_timeout_retry(order_id, next_retry_time) "
            + "VALUES(#{orderId}, DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 30 SECOND)) "
            + "ON DUPLICATE KEY UPDATE next_retry_time = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 30 SECOND), "
            + "update_time = CURRENT_TIMESTAMP")
    int scheduleRetry(@Param("orderId") Long orderId);

    @Update("UPDATE tb_order_timeout_retry SET claim_token = #{token}, "
            + "claim_until = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 10 MINUTE), "
            + "update_time = CURRENT_TIMESTAMP WHERE next_retry_time <= CURRENT_TIMESTAMP "
            + "AND (claim_until IS NULL OR claim_until <= CURRENT_TIMESTAMP) "
            + "ORDER BY next_retry_time, order_id LIMIT 100")
    int claimPending(@Param("token") String token);

    @Select("SELECT order_id FROM tb_order_timeout_retry WHERE claim_token = #{token} "
            + "ORDER BY order_id LIMIT 100")
    List<OrderTimeoutRetry> findClaimed(@Param("token") String token);

    @Delete("DELETE FROM tb_order_timeout_retry WHERE order_id = #{orderId} AND claim_token = #{token}")
    int deleteClaimed(@Param("orderId") Long orderId, @Param("token") String token);

    @Update("UPDATE tb_order_timeout_retry SET attempts = attempts + 1, "
            + "next_retry_time = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 30 SECOND), "
            + "claim_token = NULL, claim_until = NULL, update_time = CURRENT_TIMESTAMP "
            + "WHERE order_id = #{orderId} AND claim_token = #{token}")
    int retryLater(@Param("orderId") Long orderId, @Param("token") String token);
}

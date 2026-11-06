package com.hmdp.mapper;

import com.hmdp.entity.SeckillOrderDispatch;
import com.hmdp.entity.SeckillOrderDispatchBacklog;
import com.hmdp.micro.MapperRole;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@MapperRole.Order
public interface SeckillOrderDispatchMapper {
    @Select("SELECT completed FROM tb_seckill_order_dispatch WHERE order_id = #{orderId}")
    Integer findCompletionStatus(@Param("orderId") Long orderId);

    @Select({"<script>",
            "SELECT order_id AS orderId, completed FROM tb_seckill_order_dispatch WHERE order_id IN",
            "<foreach collection='orderIds' item='orderId' open='(' separator=',' close=')'>",
            "#{orderId}",
            "</foreach>",
            "</script>"})
    List<SeckillOrderDispatch> findCompletionStatuses(@Param("orderIds") List<Long> orderIds);

    @Insert({"<script>",
            "INSERT INTO tb_seckill_order_dispatch "
                    + "(order_id, user_id, payload, timeout_sent, timeout_retry_time, next_retry_time) VALUES",
            "<foreach collection='tasks' item='task' separator=','>",
            "(#{task.orderId}, #{task.userId}, #{task.payload}, 0, #{task.timeoutRetryTime}, CURRENT_TIMESTAMP)",
            "</foreach>",
            "ON DUPLICATE KEY UPDATE order_id = VALUES(order_id)",
            "</script>"})
    int insertRecoveredTasks(@Param("tasks") List<SeckillOrderDispatch> tasks);

    @Select("SELECT user_id FROM tb_seckill_order_dispatch WHERE order_id = #{orderId} AND completed = 0")
    Long findPendingUserId(@Param("orderId") Long orderId);

    // 投递表未单独存券 ID；库存缺失时保守阻止所有自动补热。
    @Select("SELECT EXISTS(SELECT 1 FROM tb_seckill_order_dispatch WHERE completed = 0)")
    boolean hasPending();

    @Select("SELECT EXISTS(SELECT 1 FROM tb_seckill_order_dispatch "
            + "WHERE user_id = #{userId} AND completed = 0 AND JSON_VALID(payload) = 1 "
            + "AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.voucherId')) = CAST(#{voucherId} AS CHAR))")
    boolean hasPendingForUserVoucher(@Param("userId") Long userId, @Param("voucherId") Long voucherId);

    @Select("SELECT orders.orderPendingCount, orders.orderOldestPendingAgeSeconds, "
            + "orders.orderRepeatedFailureCount, timeouts.timeoutPendingCount, "
            + "timeouts.timeoutOldestPendingAgeSeconds, timeouts.timeoutRepeatedFailureCount "
            + "FROM (SELECT COUNT(*) AS orderPendingCount, "
            + "COALESCE(MAX(TIMESTAMPDIFF(SECOND, create_time, CURRENT_TIMESTAMP)), 0) "
            + "AS orderOldestPendingAgeSeconds, "
            + "COALESCE(SUM(CASE WHEN attempts >= #{repeatedFailureThreshold} THEN 1 ELSE 0 END), 0) "
            + "AS orderRepeatedFailureCount FROM tb_seckill_order_dispatch WHERE completed = 0) orders "
            + "CROSS JOIN (SELECT COUNT(*) AS timeoutPendingCount, "
            + "COALESCE(MAX(TIMESTAMPDIFF(SECOND, timeout_retry_time, CURRENT_TIMESTAMP)), 0) "
            + "AS timeoutOldestPendingAgeSeconds, "
            + "COALESCE(SUM(CASE WHEN timeout_attempts >= #{repeatedFailureThreshold} THEN 1 ELSE 0 END), 0) "
            + "AS timeoutRepeatedFailureCount FROM tb_seckill_order_dispatch "
            + "WHERE timeout_sent = 0 AND timeout_retry_time <= CURRENT_TIMESTAMP) timeouts")
    SeckillOrderDispatchBacklog getBacklogSnapshot(@Param("repeatedFailureThreshold") int repeatedFailureThreshold);

    @Update("UPDATE tb_seckill_order_dispatch SET claim_token = #{claimToken}, "
            + "claim_until = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 10 MINUTE), "
            + "update_time = CURRENT_TIMESTAMP WHERE completed = 0 "
            + "AND next_retry_time <= CURRENT_TIMESTAMP "
            + "AND (claim_until IS NULL OR claim_until <= CURRENT_TIMESTAMP) "
            + "ORDER BY next_retry_time, order_id LIMIT 500")
    int claimPending(@Param("claimToken") String claimToken);

    @Select("SELECT order_id AS orderId, payload FROM tb_seckill_order_dispatch "
            + "WHERE completed = 0 AND claim_token = #{claimToken} ORDER BY next_retry_time, order_id")
    List<SeckillOrderDispatch> findClaimed(@Param("claimToken") String claimToken);

    @Update("UPDATE tb_seckill_order_dispatch SET completed = 1, update_time = CURRENT_TIMESTAMP "
            + ", claim_token = NULL, claim_until = NULL "
            + "WHERE order_id = #{orderId} AND completed = 0")
    int markCompleted(@Param("orderId") Long orderId);

    @Update("UPDATE tb_seckill_order_dispatch SET attempts = attempts + 1, claim_token = NULL, "
            + "claim_until = NULL, "
            + "next_retry_time = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 30 SECOND), "
            + "update_time = CURRENT_TIMESTAMP WHERE order_id = #{orderId} AND completed = 0 "
            + "AND claim_token = #{claimToken}")
    int recordAttempt(@Param("orderId") Long orderId, @Param("claimToken") String claimToken);

    @Update("UPDATE tb_seckill_order_dispatch SET timeout_claim_token = #{claimToken}, "
            + "timeout_claim_until = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 10 MINUTE), "
            + "update_time = CURRENT_TIMESTAMP WHERE timeout_sent = 0 "
            + "AND timeout_retry_time <= CURRENT_TIMESTAMP "
            + "AND (timeout_claim_until IS NULL OR timeout_claim_until <= CURRENT_TIMESTAMP) "
            + "ORDER BY timeout_retry_time, order_id LIMIT 100")
    int claimTimeoutPending(@Param("claimToken") String claimToken);

    @Select("SELECT order_id FROM tb_seckill_order_dispatch "
            + "WHERE timeout_sent = 0 AND timeout_claim_token = #{claimToken} "
            + "ORDER BY timeout_retry_time, order_id")
    List<Long> findTimeoutClaimed(@Param("claimToken") String claimToken);

    @Update("UPDATE tb_seckill_order_dispatch SET timeout_sent = 1, timeout_claim_token = NULL, "
            + "timeout_claim_until = NULL, update_time = CURRENT_TIMESTAMP "
            + "WHERE order_id = #{orderId} AND timeout_sent = 0 AND timeout_claim_token = #{claimToken}")
    int markTimeoutSent(@Param("orderId") Long orderId, @Param("claimToken") String claimToken);

    @Update("UPDATE tb_seckill_order_dispatch SET timeout_attempts = timeout_attempts + 1, "
            + "timeout_claim_token = NULL, timeout_claim_until = NULL, "
            + "timeout_retry_time = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 30 SECOND), "
            + "update_time = CURRENT_TIMESTAMP WHERE order_id = #{orderId} "
            + "AND timeout_sent = 0 AND timeout_claim_token = #{claimToken}")
    int recordTimeoutAttempt(@Param("orderId") Long orderId, @Param("claimToken") String claimToken);
}

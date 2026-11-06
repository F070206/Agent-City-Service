package com.hmdp.mapper;

import com.hmdp.micro.MapperRole;
import com.hmdp.entity.SeckillOrderReservation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@MapperRole.Order
public interface SeckillOrderGuardMapper {

    @Insert("INSERT INTO tb_seckill_order_guard (user_id, voucher_id, active_order_id) "
            + "VALUES (#{userId}, #{voucherId}, NULL) "
            + "ON DUPLICATE KEY UPDATE user_id = tb_seckill_order_guard.user_id")
    int ensureReservationRow(@Param("userId") Long userId, @Param("voucherId") Long voucherId);

    @Update("UPDATE tb_seckill_order_guard SET active_order_id = #{orderId} "
            + "WHERE user_id = #{userId} AND voucher_id = #{voucherId} AND active_order_id IS NULL")
    int reserve(@Param("userId") Long userId, @Param("voucherId") Long voucherId,
            @Param("orderId") Long orderId);

    @Select("SELECT active_order_id FROM tb_seckill_order_guard "
            + "WHERE user_id = #{userId} AND voucher_id = #{voucherId} FOR UPDATE")
    Long findActiveOrderIdForUpdate(@Param("userId") Long userId, @Param("voucherId") Long voucherId);

    @Select("SELECT active_order_id FROM tb_seckill_order_guard "
            + "WHERE user_id = #{userId} AND voucher_id = #{voucherId}")
    Long findActiveOrderId(@Param("userId") Long userId, @Param("voucherId") Long voucherId);

    @Select("SELECT user_id AS userId, active_order_id AS orderId FROM tb_seckill_order_guard "
            + "WHERE voucher_id = #{voucherId} AND active_order_id IS NOT NULL")
    List<SeckillOrderReservation> findActiveReservations(@Param("voucherId") Long voucherId);

    @Update("UPDATE tb_seckill_order_guard SET active_order_id = NULL "
            + "WHERE user_id = #{userId} AND voucher_id = #{voucherId} AND active_order_id = #{orderId}")
    int release(@Param("userId") Long userId, @Param("voucherId") Long voucherId,
            @Param("orderId") Long orderId);
}

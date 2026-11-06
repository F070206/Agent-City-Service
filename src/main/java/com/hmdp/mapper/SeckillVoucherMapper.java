package com.hmdp.mapper;

import com.hmdp.micro.MapperRole;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.dto.SeckillVoucherLookup;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
@MapperRole.Order
public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {

    @Select("SELECT s.voucher_id AS voucherId, s.stock, s.begin_time AS beginTime, "
            + "s.end_time AS endTime, v.status AS voucherStatus, v.type AS voucherType, "
            + "s.activity_version AS version "
            + "FROM tb_seckill_voucher s LEFT JOIN tb_voucher v ON v.id = s.voucher_id "
            + "WHERE s.voucher_id = #{voucherId}")
    SeckillVoucherLookup findForSeckill(Long voucherId);

    @Select("SELECT s.voucher_id AS voucherId, s.stock, s.begin_time AS beginTime, "
            + "s.end_time AS endTime, v.status AS voucherStatus, v.type AS voucherType, "
            + "s.activity_version AS version "
            + "FROM tb_seckill_voucher s LEFT JOIN tb_voucher v ON v.id = s.voucher_id")
    List<SeckillVoucherLookup> findAllForSeckill();

    @Update("UPDATE tb_seckill_voucher SET begin_time = #{beginTime}, "
            + "end_time = #{endTime}, activity_version = activity_version + 1 "
            + "WHERE voucher_id = #{voucherId}")
    int updateActivity(@Param("voucherId") Long voucherId,
            @Param("beginTime") LocalDateTime beginTime,
            @Param("endTime") LocalDateTime endTime);

}

package com.hmdp.mapper;

import com.hmdp.micro.MapperRole;

import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@MapperRole.Order
public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    @Select("SELECT o.id FROM tb_voucher_order o INNER JOIN tb_seckill_voucher s "
            + "ON s.voucher_id = o.voucher_id WHERE o.status = 1 "
            + "AND o.create_time <= #{deadline} AND o.id > #{afterId} ORDER BY o.id LIMIT #{batchSize}")
    List<Long> findExpiredSeckillOrderIds(@Param("deadline") LocalDateTime deadline,
            @Param("afterId") long afterId, @Param("batchSize") int batchSize);

}

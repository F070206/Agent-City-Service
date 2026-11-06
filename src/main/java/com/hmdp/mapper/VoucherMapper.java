package com.hmdp.mapper;

import com.hmdp.micro.MapperRole;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Voucher;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
public interface VoucherMapper extends BaseMapper<Voucher> {
    @Select("SELECT id FROM tb_voucher WHERE id > #{afterId} ORDER BY id LIMIT #{limit}")
    List<Long> selectIdsAfter(@Param("afterId") long afterId, @Param("limit") int limit);

    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);
}

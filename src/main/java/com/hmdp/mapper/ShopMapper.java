package com.hmdp.mapper;

import com.hmdp.micro.MapperRole;

import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
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
@Mapper
@MapperRole.Content
public interface ShopMapper extends BaseMapper<Shop> {
    /**
     * 分页查询所有店铺ID
     */
    @Select("SELECT id FROM tb_shop WHERE id > #{afterId} ORDER BY id LIMIT #{limit}")
    List<Long> selectIdsAfter(@Param("afterId") long afterId, @Param("limit") int limit);

    @Select("SELECT id, type_id AS typeId, x, y FROM tb_shop WHERE id > #{afterId} ORDER BY id LIMIT #{limit}")
    List<Shop> selectGeoDataAfter(@Param("afterId") long afterId, @Param("limit") int limit);


}

package com.hmdp.mapper;

import com.hmdp.micro.MapperRole;

import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
@MapperRole.Content
public interface FollowMapper extends BaseMapper<Follow> {

    @Select("SELECT id, user_id AS userId FROM tb_follow WHERE follow_user_id = #{authorId} "
            + "AND (user_id > #{afterUserId} OR (user_id = #{afterUserId} AND id > #{afterId})) "
            + "ORDER BY user_id, id LIMIT #{limit}")
    List<Follow> selectFollowerPage(@Param("authorId") Long authorId,
            @Param("afterUserId") Long afterUserId, @Param("afterId") Long afterId,
            @Param("limit") int limit);

}

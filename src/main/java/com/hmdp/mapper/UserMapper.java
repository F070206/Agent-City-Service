package com.hmdp.mapper;

import com.hmdp.micro.MapperRole;

import com.hmdp.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@MapperRole.User
public interface UserMapper extends BaseMapper<User> {

}

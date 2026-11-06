package com.hmdp.mapper;

import com.hmdp.micro.MapperRole;

import com.hmdp.entity.UserInfo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-24
 */
@MapperRole.User
public interface UserInfoMapper extends BaseMapper<UserInfo> {

}

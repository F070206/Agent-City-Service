package com.hmdp.config;

import com.hmdp.micro.MapperRole;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("micro-user")
@MapperScan(basePackages = "com.hmdp.mapper", annotationClass = MapperRole.User.class)
public class UserMapperConfiguration {
}

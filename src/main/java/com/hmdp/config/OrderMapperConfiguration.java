package com.hmdp.config;

import com.hmdp.micro.MapperRole;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("micro-order")
@MapperScan(basePackages = "com.hmdp.mapper", annotationClass = MapperRole.Order.class)
public class OrderMapperConfiguration {
}

package com.hmdp.config;

import com.hmdp.micro.MapperRole;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@Profile("micro-content")
@EnableScheduling
@MapperScan(basePackages = "com.hmdp.mapper", annotationClass = MapperRole.Content.class)
public class ContentMapperConfiguration {
}

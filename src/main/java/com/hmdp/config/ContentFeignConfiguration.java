package com.hmdp.config;

import com.hmdp.client.UserFeignClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("micro-content")
@EnableFeignClients(clients = UserFeignClient.class)
public class ContentFeignConfiguration {
}

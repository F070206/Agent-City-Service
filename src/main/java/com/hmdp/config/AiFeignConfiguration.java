package com.hmdp.config;

import com.hmdp.client.ContentFeignClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("micro-ai")
@EnableFeignClients(clients = ContentFeignClient.class)
public class AiFeignConfiguration {
}

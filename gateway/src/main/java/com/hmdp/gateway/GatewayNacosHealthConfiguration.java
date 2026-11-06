package com.hmdp.gateway;

import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.cloud.nacos.discovery.actuate.health.NacosDiscoveryHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.cloud.nacos.discovery.enabled", havingValue = "true", matchIfMissing = true)
public class GatewayNacosHealthConfiguration {
    @Bean("nacosDiscovery")
    public NacosDiscoveryHealthIndicator nacosDiscoveryHealthIndicator(NacosServiceManager serviceManager) {
        return new NacosDiscoveryHealthIndicator(serviceManager);
    }
}

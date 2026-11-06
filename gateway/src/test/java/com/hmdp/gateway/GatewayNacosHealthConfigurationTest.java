package com.hmdp.gateway;

import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.naming.NamingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayNacosHealthConfigurationTest {
    @Test
    void healthIndicatorReflectsNacosNamingServiceStatus() {
        NacosServiceManager manager = mock(NacosServiceManager.class);
        NamingService namingService = mock(NamingService.class);
        when(manager.getNamingService()).thenReturn(namingService);
        when(namingService.getServerStatus()).thenReturn("DOWN", "UP");

        var indicator = new GatewayNacosHealthConfiguration()
                .nacosDiscoveryHealthIndicator(manager);

        assertEquals(Status.DOWN, indicator.health().getStatus());
        assertEquals(Status.UP, indicator.health().getStatus());
    }
}

package com.hmdp.micro;

import com.hmdp.client.InternalApiGuard;
import com.hmdp.client.InternalServiceMetrics;
import com.hmdp.client.RemoteContentCatalog;
import com.hmdp.client.RemoteUserDirectory;
import com.hmdp.client.ContentFeignClient;
import com.hmdp.client.UserFeignClient;
import com.hmdp.ai.service.impl.RedisConversationExecutionCoordinator;
import com.hmdp.component.ConversationExecutionCoordinator;
import com.hmdp.component.BloomFilterInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.filter.AssignableTypeFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

class MicroserviceIsolationTest {
    private final ServiceIsolationFilter filter = new ServiceIsolationFilter();
    private final CachingMetadataReaderFactory readerFactory = new CachingMetadataReaderFactory();

    @AfterEach
    void clearRole() {
        System.clearProperty("cityai.role");
    }

    @Test
    void contentDoesNotRegisterOrderOrAiComponents() throws Exception {
        System.setProperty("cityai.role", "content");
        assertFalse(excluded("com.hmdp.service.impl.BlogServiceImpl"));
        assertFalse(excluded("com.hmdp.client.RemoteUserDirectory"));
        assertTrue(excluded("com.hmdp.service.impl.VoucherOrderServiceImpl"));
        assertTrue(excluded("com.hmdp.ai.controller.AiAgentController"));
        assertTrue(excluded("com.hmdp.component.BloomFilterInitializer"));
        assertTrue(excluded("com.hmdp.component.OrderStockSyncMetrics"));
        assertTrue(excluded("com.hmdp.component.SeckillOrderDispatchMetrics"));
    }

    @Test
    void orderIncludesDispatchMetricsAndOtherRolesExcludeThem() throws Exception {
        System.setProperty("cityai.role", "order");
        assertFalse(excluded("com.hmdp.component.OrderStockSyncMetrics"));
        assertFalse(excluded("com.hmdp.component.SeckillOrderDispatchMetrics"));
        assertFalse(excluded("com.hmdp.component.SeckillOrderDispatchWorker"));

        System.setProperty("cityai.role", "user");
        assertTrue(excluded("com.hmdp.component.OrderStockSyncMetrics"));
        assertTrue(excluded("com.hmdp.component.SeckillOrderDispatchMetrics"));
    }

    @Test
    void aiDoesNotRegisterBusinessServices() throws Exception {
        System.setProperty("cityai.role", "ai");
        assertFalse(excluded("com.hmdp.ai.controller.AiAgentController"));
        assertFalse(excluded("com.hmdp.client.RemoteContentCatalog"));
        assertFalse(excluded("com.hmdp.client.InternalServiceMetrics"));
        assertTrue(excluded("com.hmdp.service.impl.ShopServiceImpl"));
        assertTrue(excluded("com.hmdp.service.impl.UserServiceImpl"));
    }

    @Test
    void contentRegistersMetricsRequiredByRemoteUserDirectory() throws Exception {
        System.setProperty("cityai.role", "content");

        assertFalse(excluded("com.hmdp.client.RemoteUserDirectory"));
        assertFalse(excluded("com.hmdp.client.InternalServiceMetrics"));
        assertTrue(excluded("com.hmdp.client.RemoteContentCatalog"));
    }

    @Test
    void contentContextCanConstructRemoteUserDirectoryWithScannedMetrics() {
        System.setProperty("cityai.role", "content");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("micro", "micro-content");
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.registerBean(UserFeignClient.class, () -> mock(UserFeignClient.class));
            scanClientComponents(context, RemoteUserDirectory.class);

            assertFalse(context.getBeansOfType(InternalServiceMetrics.class).isEmpty());
            assertInstanceOf(RemoteUserDirectory.class, context.getBean(RemoteUserDirectory.class));
        }
    }

    @Test
    void aiContextCanConstructRemoteContentCatalogWithScannedMetrics() {
        System.setProperty("cityai.role", "ai");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("micro", "micro-ai");
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.registerBean(ContentFeignClient.class, () -> mock(ContentFeignClient.class));
            scanClientComponents(context, RemoteContentCatalog.class);

            assertFalse(context.getBeansOfType(InternalServiceMetrics.class).isEmpty());
            assertInstanceOf(RemoteContentCatalog.class, context.getBean(RemoteContentCatalog.class));
        }
    }

    private void scanClientComponents(AnnotationConfigApplicationContext context, Class<?> clientComponent) {
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(context, false);
        scanner.addIncludeFilter(new AssignableTypeFilter(InternalServiceMetrics.class));
        scanner.addIncludeFilter(new AssignableTypeFilter(clientComponent));
        scanner.addExcludeFilter(filter);
        scanner.scan("com.hmdp.client");
        context.refresh();
    }

    @Test
    void microserviceRolesDoNotRegisterMonolithBloomWarmup() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("micro", "micro-ai");
            context.register(BloomFilterInitializer.class);
            context.refresh();

            assertFalse(context.containsBean("bloomFilterInitializer"));
        }
    }

    @Test
    void internalTokenRejectsWrongCaller() {
        InternalApiGuard guard = new InternalApiGuard("secret");
        guard.verify("secret");
        assertThrows(SecurityException.class, () -> guard.verify("wrong"));
        assertThrows(SecurityException.class, () -> guard.verify(null));
    }

    @Test
    void aiProfileSelectsDistributedConversationCoordinator() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("micro-ai");
            context.registerBean(RedissonClient.class, () -> mock(RedissonClient.class));
            context.register(ConversationExecutionCoordinator.class, RedisConversationExecutionCoordinator.class);
            context.refresh();

            assertInstanceOf(RedisConversationExecutionCoordinator.class,
                    context.getBean(ConversationExecutionCoordinator.class));
        }
    }

    private boolean excluded(String className) throws Exception {
        return filter.match(readerFactory.getMetadataReader(className), readerFactory);
    }
}

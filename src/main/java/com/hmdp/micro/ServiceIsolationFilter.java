package com.hmdp.micro;

import java.io.IOException;
import java.util.Set;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;

/** Prevents a service from registering another service's controllers and workers. */
public class ServiceIsolationFilter implements TypeFilter {
    private static final Set<String> USER = Set.of("UserController", "InternalUserController",
            "UserServiceImpl", "UserInfoServiceImpl", "LocalUserDirectory", "InternalApiGuard");
    private static final Set<String> CONTENT = Set.of("ShopController", "ShopTypeController",
            "BlogController", "BlogCommentsController", "FollowController", "UploadController",
            "InternalContentController", "BlogFeedDispatchWorker", "BlogFeedDispatchMetrics", "ShopServiceImpl", "ShopTypeServiceImpl", "BlogServiceImpl",
            "BlogCommentsServiceImpl", "FollowServiceImpl", "LocalContentCatalog",
            "RemoteUserDirectory", "InternalServiceMetrics", "InternalApiGuard", "ShopGeoInitializer");
    private static final Set<String> ORDER = Set.of("VoucherController", "VoucherOrderController",
            "PaymentController", "VoucherServiceImpl", "VoucherOrderServiceImpl",
            "PaymentServiceImpl", "SeckillVoucherServiceImpl", "SeckillStockInitializer",
            "OrderStockSyncWorker", "OrderTimeoutRecoveryWorker", "SeckillOrderDispatchWorker",
            "OrderStockSyncMetrics", "SeckillOrderDispatchMetrics", "SeckillActivityCache",
            "OrderTimeoutListener", "SeckillVoucherListener",
            "VoucherBloomInitializer");
    private static final Set<String> AI = Set.of("ChatSseServiceImpl", "ChatMessageCreatedEventListener",
            "ConversationExecutionCoordinator", "AgentLoopObserver", "RemoteContentCatalog",
            "InternalServiceMetrics", "InternalApiGuard");

    @Override
    public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
        String name = reader.getClassMetadata().getClassName();
        String simple = name.substring(name.lastIndexOf('.') + 1);
        String role = System.getProperty("cityai.role", System.getenv("CITYAI_ROLE"));
        if (role == null) {
            return name.startsWith("com.hmdp.micro.");
        }
        if (name.equals("com.hmdp.HmDianPingApplication") || name.startsWith("com.hmdp.micro.")) {
            return true;
        }
        if (name.startsWith("com.hmdp.ai.")) {
            return !"ai".equals(role);
        }
        if (name.startsWith("com.hmdp.controller.") || name.startsWith("com.hmdp.service.impl.")
                || name.startsWith("com.hmdp.component.") || name.startsWith("com.hmdp.mq.")
                || name.startsWith("com.hmdp.client.")) {
            return !switch (role) {
                case "user" -> USER.contains(simple);
                case "content" -> CONTENT.contains(simple);
                case "order" -> ORDER.contains(simple);
                case "ai" -> AI.contains(simple);
                default -> false;
            };
        }
        if (name.endsWith("OrderStockSyncConfig")) {
            return !"order".equals(role);
        }
        return false;
    }
}

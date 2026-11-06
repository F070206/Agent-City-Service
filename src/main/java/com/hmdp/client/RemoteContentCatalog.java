package com.hmdp.client;

import com.hmdp.ai.config.AiDependencyGuard;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("micro-ai")
@RequiredArgsConstructor
public class RemoteContentCatalog implements ContentCatalog {
    private final ContentFeignClient client;
    private final InternalServiceMetrics metrics;

    @Override
    public Shop shop(Long id) {
        return metrics.record("content", "shop", () -> call(() -> client.shop(id)));
    }

    @Override
    public List<Shop> shopsAfter(long afterId, int limit) {
        return metrics.record("content", "shops_page", () -> call(() -> required(client.shopsAfter(afterId, limit))));
    }

    @Override
    public List<Shop> shopsByIds(List<Long> ids) {
        return ids.isEmpty() ? List.of() : metrics.record("content", "shops_by_ids",
                () -> call(() -> required(client.shopsByIds(ids))));
    }

    @Override
    public List<Shop> nearby(Integer typeId, Integer page, Double x, Double y) {
        return metrics.record("content", "shops_nearby", () -> call(() -> required(client.nearby(typeId, page, x, y))));
    }

    @Override
    public List<Shop> search(String keyword) {
        return metrics.record("content", "shops_search", () -> call(() -> required(client.search(keyword))));
    }

    @Override
    public List<Blog> blogsAfter(long afterId, int limit) {
        return metrics.record("content", "blogs_page", () -> call(() -> required(client.blogsAfter(afterId, limit))));
    }

    @Override
    public List<Blog> blogsForShop(Long shopId, Integer limit) {
        return metrics.record("content", "blogs_for_shop", () -> call(() -> required(client.blogsForShop(shopId, limit))));
    }

    @Override
    public List<Blog> blogsForShopAfter(Long shopId, long afterId, int limit) {
        return metrics.record("content", "blogs_for_shop_page",
                () -> call(() -> required(client.blogsForShopAfter(shopId, afterId, limit))));
    }

    private <T> T call(AiDependencyGuard.CheckedSupplier<T> supplier) {
        return AiDependencyGuard.callUnchecked("ai:content", () -> {
            try {
                return supplier.get();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("内容服务调用失败", e);
            }
        });
    }

    private static <T> List<T> required(List<T> value) {
        if (value == null) {
            throw new IllegalStateException("Content service returned an empty response");
        }
        return value;
    }
}

package com.hmdp.client;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!micro-ai")
@RequiredArgsConstructor
public class LocalContentCatalog implements ContentCatalog {
    private final IShopService shopService;
    private final IBlogService blogService;

    @Override
    public Shop shop(Long id) {
        Result result = shopService.queryById(id);
        return result == null ? null : (Shop) result.getData();
    }

    @Override
    public List<Shop> shopsAfter(long afterId, int limit) {
        return shopService.lambdaQuery().gt(Shop::getId, afterId).orderByAsc(Shop::getId)
                .last("limit " + checkedLimit(limit)).list();
    }

    @Override
    public List<Shop> shopsByIds(List<Long> ids) {
        return ids.isEmpty() ? List.of() : shopService.listByIds(ids);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Shop> nearby(Integer typeId, Integer page, Double x, Double y) {
        Result result = shopService.queryShopByType(typeId, page, x, y);
        return result == null || result.getData() == null ? List.of() : (List<Shop>) result.getData();
    }

    @Override
    public List<Shop> search(String keyword) {
        return shopService.lambdaQuery().like(Shop::getName, keyword)
                .or(wrapper -> wrapper.like(Shop::getArea, keyword).or().like(Shop::getAddress, keyword))
                .last("limit 10").list();
    }

    @Override
    public List<Blog> blogsAfter(long afterId, int limit) {
        return blogService.lambdaQuery().gt(Blog::getId, afterId).orderByAsc(Blog::getId)
                .last("limit " + checkedLimit(limit)).list();
    }

    private int checkedLimit(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Page limit must be between 1 and 500");
        }
        return limit;
    }

    @Override
    public List<Blog> blogsForShop(Long shopId, Integer limit) {
        var query = blogService.lambdaQuery().eq(Blog::getShopId, shopId).orderByDesc(Blog::getLiked);
        if (limit != null) {
            query.last("limit " + Math.max(1, Math.min(limit, 100)));
        }
        return query.list();
    }

    @Override
    public List<Blog> blogsForShopAfter(Long shopId, long afterId, int limit) {
        return blogService.lambdaQuery().eq(Blog::getShopId, shopId).gt(Blog::getId, afterId)
                .orderByAsc(Blog::getId).last("limit " + checkedLimit(limit)).list();
    }
}

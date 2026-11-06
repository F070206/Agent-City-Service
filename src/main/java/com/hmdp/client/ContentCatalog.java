package com.hmdp.client;

import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import java.util.List;

/** Read-only content operations exposed to the AI service. */
public interface ContentCatalog {
    Shop shop(Long id);
    List<Shop> shopsAfter(long afterId, int limit);
    List<Shop> shopsByIds(List<Long> ids);
    List<Shop> nearby(Integer typeId, Integer page, Double x, Double y);
    List<Shop> search(String keyword);
    List<Blog> blogsAfter(long afterId, int limit);
    List<Blog> blogsForShop(Long shopId, Integer limit);
    List<Blog> blogsForShopAfter(Long shopId, long afterId, int limit);
}

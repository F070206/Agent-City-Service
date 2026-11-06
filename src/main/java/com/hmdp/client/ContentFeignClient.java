package com.hmdp.client;

import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "cityai-content")
public interface ContentFeignClient {
    @GetMapping("/internal/content/shops/{id}")
    Shop shop(@PathVariable("id") Long id);

    @GetMapping("/internal/content/shops/page")
    List<Shop> shopsAfter(@RequestParam("afterId") long afterId, @RequestParam("limit") int limit);

    @GetMapping("/internal/content/shops/ids")
    List<Shop> shopsByIds(@RequestParam("ids") List<Long> ids);

    @GetMapping("/internal/content/shops/nearby")
    List<Shop> nearby(@RequestParam("typeId") Integer typeId, @RequestParam("page") Integer page,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y);

    @GetMapping("/internal/content/shops/search")
    List<Shop> search(@RequestParam("keyword") String keyword);

    @GetMapping("/internal/content/blogs/page")
    List<Blog> blogsAfter(@RequestParam("afterId") long afterId, @RequestParam("limit") int limit);

    @GetMapping("/internal/content/blogs/shop/{id}")
    List<Blog> blogsForShop(@PathVariable("id") Long shopId,
            @RequestParam(value = "limit", required = false) Integer limit);

    @GetMapping("/internal/content/blogs/shop/{id}/page")
    List<Blog> blogsForShopAfter(@PathVariable("id") Long shopId,
            @RequestParam("afterId") long afterId, @RequestParam("limit") int limit);
}

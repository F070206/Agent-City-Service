package com.hmdp.controller;

import com.hmdp.client.ContentCatalog;
import com.hmdp.client.InternalApiGuard;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("micro-content")
@RequestMapping("/internal/content")
@RequiredArgsConstructor
public class InternalContentController {
    private final ContentCatalog catalog;
    private final InternalApiGuard guard;

    @GetMapping("/shops/{id}")
    public Shop shop(@RequestHeader("X-Internal-Token") String token, @PathVariable Long id) {
        guard.verify(token);
        return catalog.shop(id);
    }

    @GetMapping("/shops/page")
    public List<Shop> shopsAfter(@RequestHeader("X-Internal-Token") String token,
            @RequestParam long afterId, @RequestParam int limit) {
        guard.verify(token);
        return catalog.shopsAfter(afterId, limit);
    }

    @GetMapping("/shops/ids")
    public List<Shop> shopsByIds(@RequestHeader("X-Internal-Token") String token,
            @RequestParam List<Long> ids) {
        guard.verify(token);
        if (ids.size() > 500) {
            throw new IllegalArgumentException("Too many shop IDs");
        }
        return catalog.shopsByIds(ids);
    }

    @GetMapping("/shops/nearby")
    public List<Shop> nearby(@RequestHeader("X-Internal-Token") String token,
            @RequestParam Integer typeId, @RequestParam Integer page,
            @RequestParam(required = false) Double x, @RequestParam(required = false) Double y) {
        guard.verify(token);
        return catalog.nearby(typeId, page, x, y);
    }

    @GetMapping("/shops/search")
    public List<Shop> search(@RequestHeader("X-Internal-Token") String token, @RequestParam String keyword) {
        guard.verify(token);
        return catalog.search(keyword);
    }

    @GetMapping("/blogs/page")
    public List<Blog> blogsAfter(@RequestHeader("X-Internal-Token") String token,
            @RequestParam long afterId, @RequestParam int limit) {
        guard.verify(token);
        return catalog.blogsAfter(afterId, limit);
    }

    @GetMapping("/blogs/shop/{id}")
    public List<Blog> blogsForShop(@RequestHeader("X-Internal-Token") String token,
            @PathVariable Long id, @RequestParam(required = false) Integer limit) {
        guard.verify(token);
        return catalog.blogsForShop(id, limit);
    }

    @GetMapping("/blogs/shop/{id}/page")
    public List<Blog> blogsForShopAfter(@RequestHeader("X-Internal-Token") String token,
            @PathVariable Long id, @RequestParam long afterId, @RequestParam int limit) {
        guard.verify(token);
        return catalog.blogsForShopAfter(id, afterId, limit);
    }
}

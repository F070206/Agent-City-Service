package com.hmdp.component;

import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.RedisConstants;
import org.redisson.api.RBloomFilter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Rebuilds the shop GEO index from the database when a content-capable app starts. */
@Component
@Profile({"!micro", "micro-content"})
public class ShopGeoInitializer implements SmartInitializingSingleton {
    private static final int PAGE_SIZE = 1000;

    private final ShopMapper shopMapper;
    private final StringRedisTemplate redisTemplate;
    private final RBloomFilter<Long> shopBloomFilter;

    public ShopGeoInitializer(ShopMapper shopMapper, StringRedisTemplate redisTemplate,
            @Qualifier("shopBloomFilter") RBloomFilter<Long> shopBloomFilter) {
        this.shopMapper = shopMapper;
        this.redisTemplate = redisTemplate;
        this.shopBloomFilter = shopBloomFilter;
    }

    @Override
    public void afterSingletonsInstantiated() {
        shopBloomFilter.tryInit(100000L, 0.01);
        long afterId = 0;
        List<Shop> shops;
        do {
            shops = shopMapper.selectGeoDataAfter(afterId, PAGE_SIZE);
            addToGeoIndexes(shops);
            if (!shops.isEmpty()) {
                afterId = shops.get(shops.size() - 1).getId();
            }
        } while (shops.size() == PAGE_SIZE);
    }

    private void addToGeoIndexes(List<Shop> shops) {
        var byType = new java.util.LinkedHashMap<Long, List<RedisGeoCommands.GeoLocation<String>>>();
        for (Shop shop : shops) {
            if (shop.getId() != null) {
                shopBloomFilter.add(shop.getId());
            }
            if (shop.getId() == null || shop.getTypeId() == null || shop.getX() == null || shop.getY() == null) {
                continue;
            }
            byType.computeIfAbsent(shop.getTypeId(), ignored -> new ArrayList<>())
                    .add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),
                            new Point(shop.getX(), shop.getY())));
        }
        byType.forEach((typeId, locations) -> redisTemplate.opsForGeo()
                .add(RedisConstants.SHOP_GEO_KEY + typeId, locations));
    }
}

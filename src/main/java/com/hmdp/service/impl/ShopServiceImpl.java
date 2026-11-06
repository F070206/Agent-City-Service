package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.ShopCacheMetrics;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;
    @Resource
    private RBloomFilter<Long> shopBloomFilter;
    @Resource
    private ShopCacheMetrics shopCacheMetrics;
    private final ConcurrentMap<Long, GeoSyncRequest> pendingGeoSync = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Boolean> pendingBloomAdds = new ConcurrentHashMap<>();
    @Override
    public Result queryById(Long id) {
        Shop shop = cacheClient
                .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, this::mayExistInShopBloomFilter,
                        CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 7.返回
        return Result.ok(shop);
    }

    private boolean mayExistInShopBloomFilter(Long shopId) {
        if (shopBloomFilter.contains(shopId)) {
            return true;
        }
        shopCacheMetrics.recordBloomRejection();
        log.info("布隆过滤器拦截，店铺ID {} 不存在", shopId);
        return false;
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        Shop previous = getById(id);
        if (previous == null) {
            return Result.fail("店铺不存在");
        }
        if (!updateById(shop)) {
            return Result.fail("店铺不存在");
        }
        refreshShopAfterCommit(id, previous);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        return queryShopByType(typeId, current, x, y, null);
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y, String name) {
        if (typeId == null || current == null || current < 1) {
            return Result.fail("店铺类型和页码参数无效");
        }
        long maxPage = 1000L / SystemConstants.DEFAULT_PAGE_SIZE;
        if (current > maxPage) {
            return Result.fail("店铺查询最多支持前1000条");
        }
        if ((x == null) != (y == null)
                || (x != null && (!Double.isFinite(x) || x < -180 || x > 180
                || !Double.isFinite(y) || y < -90 || y > 90))) {
            return Result.fail("经纬度参数无效");
        }
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null || StrUtil.isNotBlank(name)) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .like(StrUtil.isNotBlank(name), "name", name == null ? null : name.trim())
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        long fromLong = ((long) current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        long endLong = (long) current * SystemConstants.DEFAULT_PAGE_SIZE;
        int from = (int) fromLong;
        int end = (int) endLong;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
    /**
     * 新增店铺时，同步添加到布隆过滤器
     */
    @Transactional
    @Override
    public Result saveShop(Shop shop) {
        // 保存到数据库
        if (!save(shop)) {
            return Result.fail("保存店铺失败");
        }

        // Bloom 是共享 Redis 状态，无法随数据库事务回滚；仅在提交后发布新增 ID。
        refreshShopAfterCommit(shop.getId(), null, true);
        return Result.ok(shop.getId());
    }

    private void refreshShopAfterCommit(Long shopId, Shop previous) {
        refreshShopAfterCommit(shopId, previous, false);
    }

    private void refreshShopAfterCommit(Long shopId, Shop previous, boolean addToBloom) {
        Runnable refresh = () -> {
            if (addToBloom) {
                addShopToBloom(shopId);
            }
            refreshShopCache(shopId);
            try {
                refreshShopGeo(shopId, previous);
                pendingGeoSync.remove(shopId);
            } catch (RuntimeException geoFailure) {
                pendingGeoSync.put(shopId, new GeoSyncRequest(shopId,
                        previous == null ? null : previous.getTypeId()));
                log.error("店铺数据库已提交，但 GEO 索引同步失败，将重试，shopId={}", shopId, geoFailure);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refresh.run();
                }
            });
        } else {
            refresh.run();
        }
    }

    private void addShopToBloom(Long shopId) {
        try {
            shopBloomFilter.add(shopId);
            pendingBloomAdds.remove(shopId);
            log.info("新店铺 {} 已添加到布隆过滤器", shopId);
        } catch (RuntimeException bloomFailure) {
            pendingBloomAdds.put(shopId, Boolean.TRUE);
            log.error("店铺已提交，但布隆过滤器更新失败，将重试，shopId={}", shopId, bloomFailure);
        }
    }

    @Scheduled(fixedDelayString = "${shop.bloom-sync.retry-ms:5000}")
    public void retryPendingBloomAdds() {
        pendingBloomAdds.keySet().forEach(this::addShopToBloom);
    }

    @Scheduled(fixedDelayString = "${shop.geo-sync.retry-ms:5000}")
    public void retryPendingGeoSync() {
        pendingGeoSync.forEach((shopId, request) -> {
            try {
                Shop previous = request.previousTypeId() == null ? null
                        : new Shop().setId(shopId).setTypeId(request.previousTypeId());
                refreshShopGeo(shopId, previous);
                pendingGeoSync.remove(shopId, request);
            } catch (RuntimeException geoFailure) {
                log.warn("重试店铺 GEO 索引同步失败，shopId={}", shopId, geoFailure);
            }
        });
    }

    private void refreshShopGeo(Long shopId, Shop previous) {
        Shop latest = getById(shopId);
        if (previous != null && previous.getTypeId() != null
                && !Objects.equals(previous.getTypeId(), latest == null ? null : latest.getTypeId())) {
            stringRedisTemplate.opsForGeo().remove(SHOP_GEO_KEY + previous.getTypeId(), shopId.toString());
        }
        if (latest != null && latest.getTypeId() != null && latest.getX() != null && latest.getY() != null) {
            stringRedisTemplate.opsForGeo().add(SHOP_GEO_KEY + latest.getTypeId(),
                    new org.springframework.data.geo.Point(latest.getX(), latest.getY()), shopId.toString());
        } else if (latest != null && latest.getTypeId() != null) {
            stringRedisTemplate.opsForGeo().remove(SHOP_GEO_KEY + latest.getTypeId(), shopId.toString());
        }
    }

    private void refreshShopCache(Long shopId) {
        String cacheKey = CACHE_SHOP_KEY + shopId;
        try {
            Shop latest = getById(shopId);
            if (latest == null) {
                stringRedisTemplate.delete(cacheKey);
            } else {
                cacheClient.setWithLogicalExpire(cacheKey, latest, CACHE_SHOP_TTL, TimeUnit.MINUTES);
            }
        } catch (RuntimeException refreshFailure) {
            try {
                stringRedisTemplate.delete(cacheKey);
            } catch (RuntimeException invalidationFailure) {
                refreshFailure.addSuppressed(invalidationFailure);
            }
            log.error("店铺数据库已提交，但缓存刷新失败；已尝试删除旧缓存，shopId={}", shopId, refreshFailure);
        }
    }

    private record GeoSyncRequest(Long shopId, Long previousTypeId) {
    }
}

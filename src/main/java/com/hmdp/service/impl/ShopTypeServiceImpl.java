package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private static final String SHOP_TYPE_LIST_CACHE_KEY = "cache:shop:type:list:v1";
    private static final Duration SHOP_TYPE_LIST_CACHE_TTL = Duration.ofMinutes(10);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Override
    public List<ShopType> queryTypeList() {
        String cached;
        try {
            cached = stringRedisTemplate.opsForValue().get(SHOP_TYPE_LIST_CACHE_KEY);
        } catch (RuntimeException e) {
            log.warn("读取店铺类型缓存失败，将查询数据库", e);
            return loadShopTypesFromDatabase();
        }
        if (cached != null) {
            try {
                return parseShopTypes(cached);
            } catch (RuntimeException e) {
                log.warn("解析店铺类型缓存失败，将重新查询数据库", e);
            }
        }

        RLock lock;
        try {
            lock = redissonClient.getLock("lock:" + SHOP_TYPE_LIST_CACHE_KEY);
            lock.lock();
        } catch (RuntimeException e) {
            log.warn("获取店铺类型缓存重建锁失败，将查询数据库", e);
            return loadShopTypesFromDatabase();
        }

        try {
            try {
                cached = stringRedisTemplate.opsForValue().get(SHOP_TYPE_LIST_CACHE_KEY);
                if (cached != null) {
                    return parseShopTypes(cached);
                }
            } catch (RuntimeException e) {
                log.warn("重建锁内读取店铺类型缓存失败，将查询数据库", e);
            }
            List<ShopType> typeList = loadShopTypesFromDatabase();
            try {
                stringRedisTemplate.opsForValue().set(SHOP_TYPE_LIST_CACHE_KEY,
                        JSONUtil.toJsonStr(typeList), SHOP_TYPE_LIST_CACHE_TTL);
            } catch (RuntimeException e) {
                log.warn("写入店铺类型缓存失败", e);
            }
            return typeList;
        } finally {
            try {
                lock.unlock();
            } catch (RuntimeException e) {
                log.warn("释放店铺类型缓存重建锁失败", e);
            }
        }
    }

    private List<ShopType> parseShopTypes(String cached) {
        return JSONUtil.toList(JSONUtil.parseArray(cached), ShopType.class);
    }

    private List<ShopType> loadShopTypesFromDatabase() {
        List<ShopType> typeList = query().orderByAsc("sort").list();
        return typeList == null ? Collections.emptyList() : typeList;
    }

}

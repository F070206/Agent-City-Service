package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.config.AdminApiGuard;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.SlidingWindowLimit;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 */
@Slf4j
@RestController
@RequestMapping("/shop")
@SlidingWindowLimit(globalLimit = true)
public class ShopController {

    @Resource
    public IShopService shopService;
    @Resource
    private AdminApiGuard adminApiGuard;

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {
        return shopService.queryById(id);
    }

    /**
     * 新增商铺信息
     * @param shop 商铺数据
     * @return 商铺id
     */
    @PostMapping
    public Result saveShop(@RequestHeader("X-Admin-Token") String token, @RequestBody Shop shop) {
        adminApiGuard.verify(token);
        return shopService.saveShop(shop);
    }

    /**
     * 更新商铺信息
     * @param shop 商铺数据
     * @return 无
     */
    @PutMapping
    public Result updateShop(@RequestHeader("X-Admin-Token") String token, @RequestBody Shop shop) {
        adminApiGuard.verify(token);
        // 写入数据库
        return shopService.update(shop);
    }

    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/type")
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y,
            @RequestParam(value = "name", required = false) String name
    ) {
        log.info("根据商铺类型分页查询商铺信息，type:{},name:{},x:{},y:{},当前页数：{},",typeId,name,x,y,current);
       return shopService.queryShopByType(typeId, current, x, y, name);
    }

    /**
     * 根据商铺名称关键字分页查询商铺信息
     * @param name 商铺名称关键字
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        if (current == null || current < 1) {
            return Result.fail("店铺查询页码参数无效");
        }
        long maxPage = 1000L / SystemConstants.MAX_PAGE_SIZE;
        if (current > maxPage) {
            return Result.fail("店铺查询最多支持前1000条");
        }
        log.info("根据商铺类型分页查询商铺信息，name:{}",name);
        // 根据类型分页查询
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }
}

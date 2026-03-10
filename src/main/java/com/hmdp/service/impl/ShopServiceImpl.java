package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

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
    /**
     * 根据id查询商铺信息
     * 根据需求不同采取不同的缓存策略
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        // 缓存空字符串解决缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        // 互斥锁解决缓存击穿
//        Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        // 逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        return Result.ok(shop);
    }


    /**
     * 更新商铺信息
     * 先更新数据库中的商铺信息
     * 再删除redis中的商铺缓存
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null) {
            return Result.fail("商铺id不能为空！");
        }
        // 先更新数据库中的商铺信息
        updateById(shop);
        // 再删除redis中的商铺缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

}

package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

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
    /**
     * 根据id查询商铺信息
     * 根据需求不同采取不同的缓存策略
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        // 缓存null解决缓存穿透
        // Shop shop = queryWithPassThrough(id);
        // 互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
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

    /**
     * 防止缓存穿透的查询
     * 尝试从redis中查询商铺信息，如果存在直接返回
     * 如果不存在，根据id查询数据库
     * 如果数据库中不存在，缓存空对象并返回错误信息
     * 如果数据库中存在，写入redis并返回
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        // 尝试从redis中查询商铺信息，如果存在直接返回
        String shopCache = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 判断是否存在isNotBlank的优越性
        if(StrUtil.isNotBlank(shopCache)) {
            // 存在，直接返回
            Shop shop = JSONUtil.toBean(shopCache, Shop.class);
            return shop;
        }
        if(shopCache != null) {
            // 存在，但值为null，说明数据库中不存在，返回错误信息
            return null;
        }
        // 不存在，根据id查询数据库
        Shop shop = getById(id);
        // 如果数据库中不存在，返回错误信息
        if(shop == null) {
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 如果数据库中存在，写入redis并返回
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }


    /**
     * 尝试获取互斥锁
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    /**
     * 释放互斥锁
     * @param key
     */
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 互斥锁解决缓存击穿的查询
     *  从redis中查询商铺信息，如果存在直接返回
     *  然后判断是不是null
     *  是null的话获取互斥锁，判断是否获取成功
     *  失败就休眠并重试
     *  成功的话根据id查询数据库
     * @param id
     * @return
     */
    private Shop queryWithMutex(Long id) {
        // 尝试从redis中查询商铺信息，如果存在直接返回
        String shopCache = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 判断是否存在isNotBlank的优越性
        if(StrUtil.isNotBlank(shopCache)) {
            // 存在，直接返回
            Shop shop = JSONUtil.toBean(shopCache, Shop.class);
            return shop;
        }
        if(shopCache != null) {
            // 存在，但值为null，说明数据库中不存在，返回错误信息
            return null;
        }
        // 不存在，获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 判断是否获取成功
            if(!isLock) {
                // 获取失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            shop = getById(id);
            // 如果数据库中不存在，返回错误信息
            if(shop == null) {
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 如果数据库中存在，写入redis并返回
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }

}

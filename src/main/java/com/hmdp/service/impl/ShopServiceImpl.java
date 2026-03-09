package com.hmdp.service.impl;

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
     * 尝试从redis中查询商铺信息，如果存在直接返回
     * 如果不存在，根据id查询数据库
     * 如果数据库中不存在，缓存空对象并返回错误信息
     * 如果数据库中存在，写入redis并返回
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        // 尝试从redis中查询商铺信息，如果存在直接返回
        String shopCache = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 判断是否存在isNotBlank的优越性
        if(StrUtil.isNotBlank(shopCache)) {
            // 存在，直接返回
            Shop shop = JSONUtil.toBean(shopCache, Shop.class);
            return Result.ok(shop);
        }
        if(shopCache != null) {
            // 存在，但值为null，说明数据库中不存在，返回错误信息
            return Result.fail("商铺不存在！");
        }
        // 不存在，根据id查询数据库
        Shop shop = getById(id);
        // 如果数据库中不存在，返回错误信息
        if(shop == null) {
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("商铺不存在！");
        }
        // 如果数据库中存在，写入redis并返回
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
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

package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.ReactiveSetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 查询店铺类型
     * 先从redis中查询店铺类型列表，如果存在直接返回
     * 如果不存在，从数据库中查询店铺类型列表
     * 如果数据库中不存在，返回错误信息
     * 如果数据库中存在，写入redis并返回
     * 但是数据查询的结果为List<ShopType>，无法直接写入redis中，因此需要将List<ShopType>转换为JSON字符串
     * @return
     */
    @Override
    public Result queryList() {
        String typeJson = stringRedisTemplate.opsForValue().get(CACHE_TYPE_KEY);
        if(StrUtil.isNotBlank(typeJson)) {
            List<ShopType> shopTypeList = JSONUtil.toList(typeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }
        // 从数据库中查询店铺类型列表
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        if(shopTypeList == null || shopTypeList.size() == 0) {
            return Result.fail("店铺类型不存在！");
        }
        // 将List<ShopType>转换为JSON字符串，并写入redis中
        stringRedisTemplate.opsForValue().set(CACHE_TYPE_KEY, JSONUtil.toJsonStr(shopTypeList), CACHE_TYPE_TTL, TimeUnit.MINUTES);

        return Result.ok(shopTypeList);
    }
}

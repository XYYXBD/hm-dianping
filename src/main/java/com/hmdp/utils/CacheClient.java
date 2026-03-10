package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 普通写入
     * 将任意Java对象序列化为JSON字符串，并保存到Redis中
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 逻辑过期时间写入
     * 将任意Java对象序列化为JSON字符串，并保存到Redis中，并设置逻辑过期时间
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 查询工具（缓存空字符串）
     * 防止缓存穿透
     * 尝试从redis中查询信息，如果存在直接返回
     * 如果不存在，根据id查询数据库
     * 如果数据库中不存在，缓存空对象并返回错误信息
     * 如果数据库中存在，写入redis并返回
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if(json != null) {
            return null;
        }
        // 不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 如果数据库中不存在，返回错误信息
        if(r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 如果数据库中存在，写入redis并返回
        this.set(key, r, time, unit);
        return r;
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
     *  查询（互斥锁）
     *  解决缓存击穿
     *  从redis中查询信息，如果存在直接返回
     *  然后判断是不是null
     *  是null的话获取互斥锁，判断是否获取成功
     *  失败就休眠并重试
     *  成功的话根据id查询数据库
     * @param keyPrefix
     * @param lockPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     */
    public  <R, ID> R queryWithMutex(String keyPrefix, String lockPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 尝试从redis中查询信息，如果存在直接返回
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if(StrUtil.isNotBlank(json)) {
            // 存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        if(json != null) {
            // 存在，但值为null，说明数据库中不存在，返回错误信息
            return null;
        }
        // 不存在，获取互斥锁
        String lockKey = lockPrefix + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 判断是否获取成功
            if(!isLock) {
                // 获取失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, lockPrefix, id, type, dbFallback, time, unit);
            }
            r = dbFallback.apply(id);
            // 如果数据库中不存在，返回错误信息
            if(r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 如果数据库中存在，写入redis并返回
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unLock(lockKey);
        }
        return r;
    }

    /**
     * 查询（逻辑过期）
     * 解决缓存击穿
     * 从redis中查询信息，如果不存在直接返回
     * 然后判断是否过期
     * 没有过期的话直接返回
     * 过期的话获取互斥锁，判断是否获取成功
     * 成功的话开启独立线程，查询数据库，写入redis，释放互斥锁
     * 失败就直接返回过期的商铺信息
     * @param keyPrefix
     * @param lockPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, String lockPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 从redis中查询信息，如果存在直接返回
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if(StrUtil.isBlank(json)) {
            // 不存在，说明不是热点信息，直接返回
            return null;
        }
        // 存在，解析shopCache中的逻辑过期时间和商铺信息
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 没有过期的话直接返回
            return r;
        }
        // 过期的话获取互斥锁，判断是否获取成功
        String lockKey = lockPrefix + id;
        boolean isLock = tryLock(lockKey);
        if(isLock) {
            // 获取成功的话开启独立线程，查询数据库，写入redis，释放互斥锁
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        // 直接返回过期的商铺信息
        return r;
    }

}

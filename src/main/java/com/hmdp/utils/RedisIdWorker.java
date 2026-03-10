package com.hmdp.utils;

import io.netty.util.internal.ConcurrentSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    private final static long BEGIN_TIMESTAMP = 1672531200L; // 2023-01-01 00:00:00
    private final static int COUNT_BITS = 32; // 序列号占用的位数

    /**
     * 使用Redis的自增功能生成序列号
     * 每个keyPrefix对应一个自增器，保证在分布式环境下生成的ID唯一
     * 为了保证32位的序列号不会被用完，我们可以在每天的凌晨重制自增器？
     * 每次调用nextId方法时，先生成一个时间戳，然后从Redis中获取对应keyPrefix的自增值，最后将时间戳和自增值拼接成一个64位的ID返回
     * @param keyPrefix
     * @return
     */
    public long nextId(String keyPrefix) {
        // 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(java.time.ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        // 生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        // 拼接并返回
        return timestamp << COUNT_BITS | count;
    }
}

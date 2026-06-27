package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cglib.core.internal.Function;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Component
@Slf4j
@AllArgsConstructor
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    //创建10线程的线程池，线程创建需要消耗大量资源，复用线程减少消耗
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 设置 Redis 过期时间
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    /**
     * 设置 Redis 逻辑过期时间
     */
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData)); //不设置ttl

    }

    /**
     * 利用逻辑过期解决缓存击穿问题
     */
    public <R, ID> R queryWithLogicExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 因为是逻辑过期，缓存重建时会缓存空数据，如果为空则直接返回
        if (StrUtil.isBlank(json)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((String) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //缓存未过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        //缓存过期
        //获取互斥锁
        String lock_key = LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lock_key);
        //获取锁
        if (flag) {
            // DoubleCheck 防止缓存重建
            if (expireTime.isAfter(LocalDateTime.now())) {
                return r;
            }
            //
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R apply = dbFallback.apply(id);
                    this.setWithLogicExpire(key, apply, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lock_key);
                }
            });


        }
        //未获取和当前线程,返回旧数据
        return r;


    }


    /**
     * 通过互斥锁，获取数据
     *
     */
    public <R, ID> R queryWithMuteX(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        //缓存中查询数据
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }
        String lock_key = LOCK_SHOP_KEY + id;
        R r = null;
        //获取互斥锁
        try {
            boolean flag = tryLock(lock_key);
            // 获取互斥锁失败
            if (!flag) {
                Thread.sleep(20);
                return queryWithMuteX(keyPrefix, id, type, dbFallback, time, unit);
            }
            //doublecheck 在获取锁时间段内 其他线程写入数据。
            String lockJson = stringRedisTemplate.opsForValue().get(key);
            if (lockJson != null) {
                return JSONUtil.toBean(lockJson, type);
            }

            //查询数据库
            r = dbFallback.apply(id);
            if (r == null) {
                this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //设置缓存
            this.set(key, JSONUtil.toJsonStr(r), time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lock_key);
        }
        return r;

    }

    /**
     * 利用缓存空值的方式解决缓存穿透问题
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        //空值
        if (json != null) {
            return null;
        }
        //未命中,查询数据库
        R r = dbFallback.apply(id);
        if (r == null) {
            this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, time, unit);
        return r;

    }


    /**
     * 设置互斥锁,key
     *
     * @param key 锁的名称
     * @return true-获得锁；false-未获得锁
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS); //不存在的时候才能赋值，返回true；存在返回false
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key
     * @return
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}

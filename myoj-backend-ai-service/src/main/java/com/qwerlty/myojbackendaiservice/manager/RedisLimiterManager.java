package com.qwerlty.myojbackendaiservice.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RedisLimiterManager {

    private final StringRedisTemplate redisTemplate;
    private final int hourlyLimit;

    public RedisLimiterManager(StringRedisTemplate redisTemplate,
                               @Value("${myoj.ai.rate-limit.per-user-per-hour:10}") int hourlyLimit) {
        this.redisTemplate = redisTemplate;
        this.hourlyLimit = hourlyLimit;
    }

    /** Redis 暂时不可用时降级为放行，任务消费者并发仍会保护模型端。 */
    public boolean tryAcquire(Long userId) {
        long epochHour = Instant.now().getEpochSecond() / 3600;
        String key = "ai:feedback:rate:" + userId + ":" + epochHour;
        try {
            Long current = redisTemplate.opsForValue().increment(key);
            if (current != null && current == 1L) {
                redisTemplate.expire(key, 2, TimeUnit.HOURS);
            }
            return current == null || current <= hourlyLimit;
        } catch (RuntimeException exception) {
            log.warn("Redis rate limiter unavailable; allowing AI task creation");
            return true;
        }
    }

    /** 任务未实际创建时退还本次配额，主要处理唯一键并发竞争。 */
    public void refund(Long userId) {
        long epochHour = Instant.now().getEpochSecond() / 3600;
        String key = "ai:feedback:rate:" + userId + ":" + epochHour;
        try {
            Long current = redisTemplate.opsForValue().decrement(key);
            if (current != null && current <= 0) {
                redisTemplate.delete(key);
            }
        } catch (RuntimeException exception) {
            log.warn("Redis rate limiter refund failed");
        }
    }
}

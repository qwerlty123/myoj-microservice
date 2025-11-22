package com.qwerlty.myojbackendaiservice.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
public class GenerationRateLimiter {
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local userCount = redis.call('INCR', KEYS[1])
            if userCount == 1 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
            local globalCount = redis.call('INCR', KEYS[2])
            if globalCount == 1 then redis.call('EXPIRE', KEYS[2], ARGV[3]) end
            if userCount > tonumber(ARGV[1]) or globalCount > tonumber(ARGV[2]) then
              local userRemaining = redis.call('DECR', KEYS[1])
              if userRemaining <= 0 then redis.call('DEL', KEYS[1]) end
              local globalRemaining = redis.call('DECR', KEYS[2])
              if globalRemaining <= 0 then redis.call('DEL', KEYS[2]) end
              return 0
            end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REFUND_SCRIPT = new DefaultRedisScript<>("""
            for index = 1, 2 do
              if redis.call('EXISTS', KEYS[index]) == 1 then
                local remaining = redis.call('DECR', KEYS[index])
                if remaining <= 0 then redis.call('DEL', KEYS[index]) end
              end
            end
            return 1
            """, Long.class);
    private final StringRedisTemplate redisTemplate;
    private final int perUserLimit;
    private final int globalLimit;

    public GenerationRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${myoj.ai.generation.rate-limit.per-user-per-hour:10}") int perUserLimit,
            @Value("${myoj.ai.generation.rate-limit.global-per-hour:30}") int globalLimit) {
        this.redisTemplate = redisTemplate;
        this.perUserLimit = perUserLimit;
        this.globalLimit = globalLimit;
    }

    public boolean tryAcquire(Long userId) {
        long hour = Instant.now().getEpochSecond() / 3600;
        String userKey = "ai:generation:rate:user:" + userId + ":" + hour;
        String globalKey = "ai:generation:rate:global:" + hour;
        try {
            Long allowed = redisTemplate.execute(ACQUIRE_SCRIPT, List.of(userKey, globalKey),
                    String.valueOf(perUserLimit), String.valueOf(globalLimit), "7200");
            return Long.valueOf(1L).equals(allowed);
        } catch (RuntimeException exception) {
            log.warn("Generation rate limiter unavailable; relying on bounded worker pools");
            return true;
        }
    }

    public void refund(Long userId) {
        long hour = Instant.now().getEpochSecond() / 3600;
        try {
            redisTemplate.execute(REFUND_SCRIPT, List.of(
                    "ai:generation:rate:user:" + userId + ":" + hour,
                    "ai:generation:rate:global:" + hour));
        } catch (RuntimeException exception) {
            log.warn("Generation rate limiter refund failed");
        }
    }

}

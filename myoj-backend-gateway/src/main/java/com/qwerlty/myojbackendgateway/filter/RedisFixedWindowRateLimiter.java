package com.qwerlty.myojbackendgateway.filter;

import com.qwerlty.myojbackendgateway.config.UserRateLimitProperties;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

@Component
public class RedisFixedWindowRateLimiter {

    private static final String KEY_PREFIX = "myoj:gateway:rate-limit:";

    /**
     * Returns -1 when allowed, otherwise the key TTL in milliseconds. INCR and PEXPIRE are
     * kept in one script so a gateway crash cannot leave a counter without an expiry.
     */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1]) "
                    + "if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end "
                    + "if current > tonumber(ARGV[1]) then "
                    + "  local ttl = redis.call('PTTL', KEYS[1]) "
                    + "  if ttl < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[2]); ttl = tonumber(ARGV[2]) end "
                    + "  return ttl "
                    + "end "
                    + "return -1",
            Long.class);

    private final ReactiveStringRedisTemplate redisTemplate;

    public RedisFixedWindowRateLimiter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Mono<Decision> acquire(UserRateLimitProperties.Rule rule, String userId) {
        long windowMillis = rule.getWindow().toMillis();
        String key = KEY_PREFIX + rule.getId() + ":" + userId;
        return redisTemplate.execute(ACQUIRE_SCRIPT, Collections.singletonList(key),
                        Arrays.asList(Long.toString(rule.getLimit()), Long.toString(windowMillis)))
                .next()
                .switchIfEmpty(Mono.error(new IllegalStateException("Redis rate limiter returned no result")))
                .map(result -> result < 0
                        ? Decision.allowed()
                        : Decision.blocked(Duration.ofMillis(Math.max(1, result))));
    }

    public static final class Decision {

        private final boolean allowed;

        private final boolean backendAvailable;

        private final Duration retryAfter;

        private Decision(boolean allowed, boolean backendAvailable, Duration retryAfter) {
            this.allowed = allowed;
            this.backendAvailable = backendAvailable;
            this.retryAfter = retryAfter;
        }

        public static Decision allowed() {
            return new Decision(true, true, Duration.ZERO);
        }

        public static Decision blocked(Duration retryAfter) {
            return new Decision(false, true, retryAfter);
        }

        public static Decision unavailable() {
            return new Decision(false, false, Duration.ofSeconds(1));
        }

        public boolean isAllowed() {
            return allowed;
        }

        public boolean isBackendAvailable() {
            return backendAvailable;
        }

        public Duration getRetryAfter() {
            return retryAfter;
        }
    }
}

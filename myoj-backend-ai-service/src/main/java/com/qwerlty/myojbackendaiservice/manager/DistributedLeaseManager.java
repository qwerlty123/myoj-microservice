package com.qwerlty.myojbackendaiservice.manager;

import jakarta.annotation.PreDestroy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Redis ZSET based cross-instance bounded lease. */
@Component
public class DistributedLeaseManager {
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then return 0 end
            redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "return redis.call('ZREM', KEYS[1], ARGV[1])", Long.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('ZSCORE', KEYS[1], ARGV[1]) == false then return 0 end
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public DistributedLeaseManager(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Lease acquire(String resource, int limit, Duration ttl) {
        String key = "myoj:ai:lease:" + resource;
        String token = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Long acquired = redis.execute(ACQUIRE, List.of(key), String.valueOf(now), String.valueOf(limit),
                String.valueOf(now + ttl.toMillis()), token, String.valueOf(ttl.toMillis() * 2));
        if (!Long.valueOf(1).equals(acquired)) {
            throw new java.util.concurrent.RejectedExecutionException(resource + " concurrency exhausted");
        }
        return new Lease(redis, key, token, ttl, heartbeat);
    }

    public long inUse(String resource) {
        Long count = redis.opsForZSet().count("myoj:ai:lease:" + resource,
                System.currentTimeMillis(), Double.POSITIVE_INFINITY);
        return count == null ? 0L : count;
    }

    @PreDestroy
    public void shutdown() {
        heartbeat.shutdown();
    }

    public static final class Lease implements AutoCloseable {
        private final StringRedisTemplate redis;
        private final String key;
        private final String token;
        private final ScheduledFuture<?> renewal;
        private volatile boolean closed;

        private Lease(StringRedisTemplate redis, String key, String token, Duration ttl,
                      ScheduledExecutorService heartbeat) {
            this.redis = redis;
            this.key = key;
            this.token = token;
            long ttlMs = Math.max(3_000L, ttl.toMillis());
            long intervalMs = Math.max(1_000L, ttlMs / 3L);
            this.renewal = heartbeat.scheduleAtFixedRate(() -> renew(ttlMs),
                    intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }

        private void renew(long ttlMs) {
            if (closed) return;
            try {
                redis.execute(RENEW, List.of(key), token,
                        String.valueOf(System.currentTimeMillis() + ttlMs), String.valueOf(ttlMs * 2L));
            } catch (RuntimeException ignored) {
                // A lost heartbeat is safe: the original TTL eventually frees the permit.
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            renewal.cancel(false);
            try {
                redis.execute(RELEASE, List.of(key), token);
            } catch (RuntimeException ignored) {
                // TTL guarantees eventual release after a process/network failure.
            }
        }
    }
}

package com.qwerlty.myojbackendaiservice.job;

import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationLane;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/** Uses the durable task ledger to repair a missing or stale Redis daily-cost counter. */
@Component
public class GenerationCostReconciliationJob {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DefaultRedisScript<Long> SET_MAX = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local durable = tonumber(ARGV[1])
            if durable > current then redis.call('SET', KEYS[1], durable, 'EX', ARGV[2]) return durable end
            return current
            """, Long.class);

    private final AiProblemGenerationTaskMapper taskMapper;
    private final StringRedisTemplate redis;

    public GenerationCostReconciliationJob(AiProblemGenerationTaskMapper taskMapper,
                                           StringRedisTemplate redis) {
        this.taskMapper = taskMapper;
        this.redis = redis;
    }

    @Scheduled(fixedDelayString = "${myoj.ai.generation.cost-reconciliation-interval-ms:60000}")
    public void reconcile() {
        LocalDate today = LocalDate.now(ZONE);
        Date start = Date.from(today.atStartOfDay(ZONE).toInstant());
        Date end = Date.from(today.plusDays(1).atStartOfDay(ZONE).toInstant());
        for (GenerationLane lane : GenerationLane.values()) {
            Long durable = taskMapper.sumEstimatedCost(lane.name(), start, end);
            redis.execute(SET_MAX, List.of("myoj:ai:cost:" + today + ":" + lane.name()),
                    String.valueOf(durable == null ? 0L : durable),
                    String.valueOf(Duration.ofDays(3).getSeconds()));
        }
    }
}

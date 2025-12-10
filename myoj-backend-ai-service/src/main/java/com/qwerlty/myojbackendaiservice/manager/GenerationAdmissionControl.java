package com.qwerlty.myojbackendaiservice.manager;

import com.qwerlty.myojbackendaiservice.common.ErrorCode;
import com.qwerlty.myojbackendaiservice.exception.BusinessException;
import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationLane;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationQuotaVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * AI 创作唯一的准入边界。额度、排队槽位与运行锁都在 Redis Lua 中原子变更；
 * Redis 不可用时拒绝新任务，避免故障时无限放大模型流量。
 */
@Slf4j
@Component
public class GenerationAdmissionControl {
    private static final ZoneId QUOTA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String PREFIX = "myoj:ai:generation:";

    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 2 end
            local cost = tonumber(ARGV[1])
            local isAdmin = ARGV[8] == '1'
            local isReview = ARGV[9] == '1'
            local used = tonumber(redis.call('GET', KEYS[2]) or '0')
            local pending = tonumber(redis.call('GET', KEYS[3]) or '0')
            local total = tonumber(redis.call('GET', KEYS[4]) or '0')
            local lane = tonumber(redis.call('GET', KEYS[5]) or '0')
            local spent = tonumber(redis.call('GET', KEYS[7]) or '0')
            if tonumber(ARGV[10]) > 0 and spent >= tonumber(ARGV[10]) then return -5 end
            if (not isAdmin) and used + cost > tonumber(ARGV[2]) then return -1 end
            if (not isReview) and pending >= tonumber(ARGV[3]) then return -2 end
            if total >= tonumber(ARGV[4]) then return -3 end
            if lane >= tonumber(ARGV[5]) then return -4 end
            if (not isAdmin) and cost > 0 then redis.call('INCRBY', KEYS[2], cost) end
            if not isReview then redis.call('INCR', KEYS[3]) end
            redis.call('INCR', KEYS[4])
            redis.call('INCR', KEYS[5])
            redis.call('HSET', KEYS[1], 'state', 'PENDING', 'cost', cost,
              'charged', isAdmin and '0' or '1', 'review', isReview and '1' or '0')
            for i = 1, 5 do redis.call('EXPIRE', KEYS[i], tonumber(ARGV[6])) end
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> START = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'state') ~= 'PENDING' then return 0 end
            local isReview = redis.call('HGET', KEYS[1], 'review') == '1'
            if not isReview then
              if redis.call('EXISTS', KEYS[2]) == 1 then return -1 end
              redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
              local pending = redis.call('DECR', KEYS[3])
              if pending <= 0 then redis.call('DEL', KEYS[3]) end
            end
            redis.call('HSET', KEYS[1], 'state', 'RUNNING')
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> REVERT_START = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'state') ~= 'RUNNING' then return 0 end
            local isReview = redis.call('HGET', KEYS[1], 'review') == '1'
            if not isReview then
              if redis.call('GET', KEYS[2]) == ARGV[1] then redis.call('DEL', KEYS[2]) end
              redis.call('INCR', KEYS[3])
            end
            redis.call('HSET', KEYS[1], 'state', 'PENDING')
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> SETTLE = new DefaultRedisScript<>("""
            local state = redis.call('HGET', KEYS[1], 'state')
            if not state then return -1 end
            if state == 'SETTLED' then return 2 end
            local isReview = redis.call('HGET', KEYS[1], 'review') == '1'
            if not isReview and state == 'PENDING' then
              local pending = redis.call('DECR', KEYS[3])
              if pending <= 0 then redis.call('DEL', KEYS[3]) end
            elseif not isReview and state == 'RUNNING' and redis.call('GET', KEYS[2]) == ARGV[1] then
              redis.call('DEL', KEYS[2])
            end
            if state ~= 'TERMINAL' then
              for i = 4, 5 do
                local remaining = redis.call('DECR', KEYS[i])
                if remaining <= 0 then redis.call('DEL', KEYS[i]) end
              end
            end
            if ARGV[2] == '1' and redis.call('HGET', KEYS[1], 'charged') == '1' then
              local cost = tonumber(redis.call('HGET', KEYS[1], 'cost') or '0')
              local used = redis.call('DECRBY', KEYS[6], cost)
              if used <= 0 then redis.call('DEL', KEYS[6]) end
            end
            redis.call('HSET', KEYS[1], 'state', 'SETTLED', 'refunded', ARGV[2])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RESTORE_RESERVATION = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 2 end
            redis.call('HSET', KEYS[1], 'state', ARGV[1], 'cost', ARGV[2],
              'charged', ARGV[3], 'review', ARGV[4], 'restored', '1')
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]))
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final AiProblemGenerationTaskMapper taskMapper;
    private final int dailyCredits;
    private final int maxPendingPerUser;
    private final int totalCapacity;
    private final int publicCapacity;
    private final int reviewCapacity;
    private final long runningLeaseMs;
    private final int reservationTtlSeconds;
    private final long publicBudgetMicros;
    private final long reviewBudgetMicros;

    public GenerationAdmissionControl(StringRedisTemplate redis,
                                      AiProblemGenerationTaskMapper taskMapper,
                                      @Value("${myoj.ai.generation.quota.daily-credits:10}") int dailyCredits,
                                      @Value("${myoj.ai.generation.capacity.max-pending-per-user:2}") int maxPendingPerUser,
                                      @Value("${myoj.ai.generation.capacity.total:200}") int totalCapacity,
                                      @Value("${myoj.ai.generation.capacity.public:180}") int publicCapacity,
                                      @Value("${myoj.ai.generation.capacity.review:20}") int reviewCapacity,
                                      @Value("${myoj.ai.generation.running-timeout-ms:1380000}") long runningLeaseMs,
                                      @Value("${myoj.ai.generation.cost.public-daily-budget-micros:0}") long publicBudgetMicros,
                                      @Value("${myoj.ai.generation.cost.review-daily-budget-micros:0}") long reviewBudgetMicros) {
        if (publicCapacity + reviewCapacity > totalCapacity) {
            throw new IllegalArgumentException("AI lane capacity cannot exceed total capacity");
        }
        this.redis = redis;
        this.taskMapper = taskMapper;
        this.dailyCredits = dailyCredits;
        this.maxPendingPerUser = maxPendingPerUser;
        this.totalCapacity = totalCapacity;
        this.publicCapacity = publicCapacity;
        this.reviewCapacity = reviewCapacity;
        this.runningLeaseMs = runningLeaseMs + Duration.ofMinutes(2).toMillis();
        this.reservationTtlSeconds = (int) Duration.ofDays(35).getSeconds();
        this.publicBudgetMicros = publicBudgetMicros;
        this.reviewBudgetMicros = reviewBudgetMicros;
    }

    public Reservation reserve(Long userId, String role, AuthoringTaskType type, String requestKey) {
        GenerationLane lane = GenerationLane.forType(type);
        int cost = credits(type);
        LocalDate quotaDate = LocalDate.now(QUOTA_ZONE);
        List<String> keys = keys(userId, requestKey, lane, quotaDate);
        try {
            ensureBaseline(userId, lane, quotaDate);
            Long result = redis.execute(RESERVE, keys, String.valueOf(cost), String.valueOf(dailyCredits),
                    String.valueOf(maxPendingPerUser), String.valueOf(totalCapacity),
                    String.valueOf(lane == GenerationLane.ADMIN_REVIEW ? reviewCapacity : publicCapacity),
                    String.valueOf(reservationTtlSeconds), userId.toString(),
                    "admin".equals(role) ? "1" : "0", lane == GenerationLane.ADMIN_REVIEW ? "1" : "0",
                    String.valueOf(lane == GenerationLane.ADMIN_REVIEW ? reviewBudgetMicros : publicBudgetMicros));
            if (Long.valueOf(1).equals(result)) {
                return new Reservation(requestKey, lane, "admin".equals(role) ? 0 : cost, quotaDate);
            }
            if (Long.valueOf(2).equals(result)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "相同请求正在创建，请稍后查询任务历史");
            }
            if (Long.valueOf(-1).equals(result)) throw new BusinessException(ErrorCode.AI_DAILY_QUOTA_EXCEEDED);
            if (Long.valueOf(-2).equals(result)) throw new BusinessException(ErrorCode.AI_USER_PENDING_LIMIT);
            if (Long.valueOf(-5).equals(result)) throw new BusinessException(ErrorCode.AI_COST_BUDGET_EXHAUSTED);
            throw new BusinessException(ErrorCode.AI_CAPACITY_FULL);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("[AI_GENERATION] admission unavailable errorType={}", exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.AI_UPSTREAM_UNAVAILABLE);
        }
    }

    public boolean tryStart(AiProblemGenerationTask task) {
        try {
            if (!Boolean.TRUE.equals(redis.hasKey(reservationKey(task.getRequestKey())))) {
                LocalDate date = quotaDate(task);
                GenerationLane lane = lane(task);
                ensureBaseline(task.getUserId(), lane, date);
                restoreReservation(task, "PENDING");
            }
            Long result = redis.execute(START,
                    List.of(reservationKey(task.getRequestKey()), runningKey(task.getUserId()), pendingKey(task.getUserId())),
                    task.getId().toString(), String.valueOf(runningLeaseMs));
            return Long.valueOf(1).equals(result);
        } catch (RuntimeException exception) {
            log.warn("[AI_GENERATION] start admission unavailable taskId={}", task.getId());
            return false;
        }
    }

    public void revertStart(AiProblemGenerationTask task) {
        try {
            redis.execute(REVERT_START,
                    List.of(reservationKey(task.getRequestKey()), runningKey(task.getUserId()), pendingKey(task.getUserId())),
                    task.getId().toString());
        } catch (RuntimeException exception) {
            log.warn("[AI_GENERATION] unable to revert running slot taskId={}", task.getId());
        }
    }

    public boolean settle(AiProblemGenerationTask task, boolean refund) {
        if (task == null || task.getRequestKey() == null) return false;
        GenerationLane lane = lane(task);
        LocalDate date = quotaDate(task);
        try {
            if (!Boolean.TRUE.equals(redis.hasKey(reservationKey(task.getRequestKey())))) {
                ensureBaseline(task.getUserId(), lane, date);
                String restoredState = task.getStatus() != null && task.getStatus() == 0 ? "PENDING"
                        : task.getStatus() != null && task.getStatus() == 1 ? "RUNNING" : "TERMINAL";
                restoreReservation(task, restoredState);
            }
            Long result = redis.execute(SETTLE, keys(task.getUserId(), task.getRequestKey(), lane, date),
                    task.getId().toString(), refund ? "1" : "0", String.valueOf(reservationTtlSeconds));
            return Long.valueOf(1).equals(result) || Long.valueOf(2).equals(result);
        } catch (RuntimeException exception) {
            log.warn("[AI_GENERATION] settlement deferred taskId={} refund={}", task.getId(), refund);
            return false;
        }
    }

    public void rollback(Long userId, String requestKey, GenerationLane lane, LocalDate quotaDate) {
        AiProblemGenerationTask task = new AiProblemGenerationTask();
        task.setId(0L);
        task.setUserId(userId);
        task.setRequestKey(requestKey);
        task.setLane(lane.name());
        task.setQuotaDate(java.sql.Date.valueOf(quotaDate));
        settle(task, true);
    }

    public GenerationQuotaVO quota(Long userId, String role) {
        LocalDate date = LocalDate.now(QUOTA_ZONE);
        if ("admin".equals(role)) return new GenerationQuotaVO(date, dailyCredits, 0, dailyCredits);
        try {
            ensureQuotaBaseline(userId, date);
            String value = redis.opsForValue().get(quotaKey(userId, date));
            int used = value == null ? 0 : Integer.parseInt(value);
            return new GenerationQuotaVO(date, dailyCredits, used, Math.max(0, dailyCredits - used));
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AI_UPSTREAM_UNAVAILABLE);
        }
    }

    public int credits(AuthoringTaskType type) {
        return switch (type) {
            case PROBLEM_DRAFT -> 3;
            case TEST_CASES -> 2;
            case QUALITY_REVIEW -> 0;
        };
    }

    private List<String> keys(Long userId, String requestKey, GenerationLane lane, LocalDate date) {
        return List.of(reservationKey(requestKey), quotaKey(userId, date), pendingKey(userId),
                PREFIX + "inflight:total", PREFIX + "inflight:lane:" + lane.name(), quotaKey(userId, date),
                "myoj:ai:cost:" + date + ":" + lane.name());
    }

    private String reservationKey(String requestKey) { return PREFIX + "reservation:" + requestKey; }
    private String quotaKey(Long userId, LocalDate date) { return PREFIX + "quota:" + date + ":" + userId; }
    private String pendingKey(Long userId) { return PREFIX + "pending:user:" + userId; }
    private String runningKey(Long userId) { return PREFIX + "running:user:" + userId; }

    private void ensureBaseline(Long userId, GenerationLane lane, LocalDate date) {
        seedCounterIfMissing(PREFIX + "inflight:total", taskMapper::countActiveForAdmission);
        seedCounterIfMissing(PREFIX + "inflight:lane:" + lane.name(),
                () -> taskMapper.countActiveForAdmissionByLane(lane.name()));
        seedCounterIfMissing(pendingKey(userId), () -> taskMapper.countPendingForAdmission(userId));
        ensureQuotaBaseline(userId, date);
        if (!Boolean.TRUE.equals(redis.hasKey(runningKey(userId)))) {
            Long runningTaskId = taskMapper.selectRunningTaskForAdmission(userId);
            if (runningTaskId != null) {
                redis.opsForValue().setIfAbsent(runningKey(userId), runningTaskId.toString(),
                        Duration.ofMillis(runningLeaseMs));
            }
        }
    }

    private void ensureQuotaBaseline(Long userId, LocalDate date) {
        if (Boolean.TRUE.equals(redis.hasKey(quotaKey(userId, date)))) return;
        Long used = taskMapper.sumQuotaForAdmission(userId, java.sql.Date.valueOf(date));
        seedCounter(quotaKey(userId, date), used == null ? 0L : used);
    }

    private void seedCounterIfMissing(String key, LongSupplier databaseValue) {
        if (!Boolean.TRUE.equals(redis.hasKey(key))) seedCounter(key, databaseValue.getAsLong());
    }

    private void seedCounter(String key, long value) {
        redis.opsForValue().setIfAbsent(key, String.valueOf(Math.max(0L, value)),
                Duration.ofSeconds(reservationTtlSeconds));
    }

    private void restoreReservation(AiProblemGenerationTask task, String state) {
        int cost = task.getQuotaCost() == null ? 0 : task.getQuotaCost();
        boolean review = lane(task) == GenerationLane.ADMIN_REVIEW;
        redis.execute(RESTORE_RESERVATION, List.of(reservationKey(task.getRequestKey())), state,
                String.valueOf(cost), cost > 0 ? "1" : "0", review ? "1" : "0",
                String.valueOf(reservationTtlSeconds));
    }

    private LocalDate quotaDate(AiProblemGenerationTask task) {
        if (task.getQuotaDate() == null) return LocalDate.now(QUOTA_ZONE);
        if (task.getQuotaDate() instanceof java.sql.Date sqlDate) return sqlDate.toLocalDate();
        return task.getQuotaDate().toInstant().atZone(QUOTA_ZONE).toLocalDate();
    }

    private GenerationLane lane(AiProblemGenerationTask task) {
        return task.getLane() == null ? GenerationLane.forType(AuthoringTaskType.parse(task.getMode()))
                : GenerationLane.valueOf(task.getLane());
    }

    public record Reservation(String requestKey, GenerationLane lane, int cost, LocalDate quotaDate) { }
}

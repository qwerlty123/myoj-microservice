package com.qwerlty.myojbackendaiservice.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.common.ErrorCode;
import com.qwerlty.myojbackendaiservice.exception.BusinessException;
import com.qwerlty.myojbackendaiservice.manager.DistributedLeaseManager;
import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationLane;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Central model-call boundary for concurrency, circuit, usage and cost. */
@Slf4j
@Component
public class AiModelGateway {
    private final DistributedLeaseManager leases;
    private final StringRedisTemplate redis;
    private final AiProblemGenerationTaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final MeterRegistry metrics;
    private final int publicConcurrency;
    private final int reviewConcurrency;
    private final long publicBudgetMicros;
    private final long reviewBudgetMicros;
    private final long inputPriceMicrosPerMillion;
    private final long outputPriceMicrosPerMillion;
    private final int circuitThreshold;
    private final long circuitOpenMs;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntil = new AtomicLong();

    public AiModelGateway(DistributedLeaseManager leases,
                          StringRedisTemplate redis,
                          AiProblemGenerationTaskMapper taskMapper,
                          ObjectMapper objectMapper,
                          MeterRegistry metrics,
                          @Value("${myoj.ai.generation.model-concurrency.public:2}") int publicConcurrency,
                          @Value("${myoj.ai.generation.model-concurrency.review:1}") int reviewConcurrency,
                          @Value("${myoj.ai.generation.cost.public-daily-budget-micros:0}") long publicBudgetMicros,
                          @Value("${myoj.ai.generation.cost.review-daily-budget-micros:0}") long reviewBudgetMicros,
                          @Value("${myoj.ai.generation.cost.input-price-micros-per-million:0}") long inputPriceMicrosPerMillion,
                          @Value("${myoj.ai.generation.cost.output-price-micros-per-million:0}") long outputPriceMicrosPerMillion,
                          @Value("${myoj.ai.generation.circuit.failure-threshold:5}") int circuitThreshold,
                          @Value("${myoj.ai.generation.circuit.open-ms:30000}") long circuitOpenMs) {
        this.leases = leases;
        this.redis = redis;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.publicConcurrency = Math.max(1, publicConcurrency);
        this.reviewConcurrency = Math.max(1, reviewConcurrency);
        this.publicBudgetMicros = publicBudgetMicros;
        this.reviewBudgetMicros = reviewBudgetMicros;
        this.inputPriceMicrosPerMillion = inputPriceMicrosPerMillion;
        this.outputPriceMicrosPerMillion = outputPriceMicrosPerMillion;
        this.circuitThreshold = Math.max(1, circuitThreshold);
        this.circuitOpenMs = Math.max(1_000L, circuitOpenMs);
    }

    public <T> T call(String operation, String input, Supplier<T> supplier) {
        return callWithUsage(operation, input,
                () -> new ModelCallResult<>(supplier.get(), null, null, null));
    }

    public <T> T callWithUsage(String operation, String input, Supplier<ModelCallResult<T>> supplier) {
        AiCallContext.Value context = AiCallContext.current();
        GenerationLane lane = context == null ? GenerationLane.PUBLIC_AUTHORING : context.lane();
        if (System.currentTimeMillis() < openUntil.get()) {
            throw new BusinessException(ErrorCode.AI_UPSTREAM_UNAVAILABLE, "模型服务熔断中");
        }
        enforceBudget(lane);
        int limit = lane == GenerationLane.ADMIN_REVIEW ? reviewConcurrency : publicConcurrency;
        long remaining = context == null ? 120_000L : Math.max(1_000L,
                Math.min(120_000L, context.deadlineEpochMs() - System.currentTimeMillis()));
        long started = System.nanoTime();
        try (DistributedLeaseManager.Lease ignored = leases.acquire(
                "model:" + lane.name(), limit, Duration.ofMillis(remaining + 30_000L))) {
            ModelCallResult<T> result = supplier.get();
            consecutiveFailures.set(0);
            recordUsage(context, lane, input, result);
            metrics.timer("ai_authoring_model_call_duration", "operation", operation, "lane", lane.name())
                    .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            metrics.counter("ai_authoring_model_calls_total", "operation", operation,
                    "lane", lane.name(), "outcome", "success").increment();
            return result.value();
        } catch (BusinessException exception) {
            metrics.counter("ai_authoring_model_calls_total", "operation", operation,
                    "lane", lane.name(), "outcome", "rejected").increment();
            throw exception;
        } catch (RuntimeException exception) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= circuitThreshold) openUntil.set(System.currentTimeMillis() + circuitOpenMs);
            metrics.counter("ai_authoring_model_calls_total", "operation", operation,
                    "lane", lane.name(), "outcome", "failed").increment();
            throw exception;
        }
    }

    private void recordUsage(AiCallContext.Value context, GenerationLane lane, String input,
                             ModelCallResult<?> result) {
        int inputTokens = result.inputTokens() == null ? estimate(input) : result.inputTokens();
        int outputTokens = result.outputTokens() == null ? estimateOutput(result.value()) : result.outputTokens();
        long cost = inputTokens * inputPriceMicrosPerMillion / 1_000_000L
                + outputTokens * outputPriceMicrosPerMillion / 1_000_000L;
        if (context != null) taskMapper.addModelUsage(
                context.taskId(), inputTokens, outputTokens, cost, result.modelName());
        if (cost > 0) recordDailyCost(lane, cost);
        metrics.counter("ai_authoring_model_tokens_total", "direction", "input", "lane", lane.name())
                .increment(inputTokens);
        metrics.counter("ai_authoring_model_tokens_total", "direction", "output", "lane", lane.name())
                .increment(outputTokens);
        metrics.counter("ai_authoring_model_cost_micros_total", "lane", lane.name()).increment(cost);
    }

    private void recordDailyCost(GenerationLane lane, long cost) {
        try {
            Long used = redis.opsForValue().increment(budgetKey(lane), cost);
            long budget = budget(lane);
            if (budget > 0 && used != null && used >= budget * 8 / 10) {
                String alertKey = budgetKey(lane) + ":alert:80";
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(alertKey, "1", Duration.ofDays(2)))) {
                    log.warn("[AI_GENERATION] daily model cost reached alert threshold lane={} usedMicros={} budgetMicros={}",
                            lane, used, budget);
                }
            }
        } catch (RuntimeException exception) {
            // The model call already consumed money. Do not repeat it merely because accounting Redis failed;
            // the DB usage row is authoritative and the reconciliation job repairs the daily counter.
            metrics.counter("ai_authoring_cost_accounting_total", "lane", lane.name(), "outcome", "deferred")
                    .increment();
            log.error("[AI_GENERATION] daily cost counter update deferred lane={}", lane);
        }
    }

    private void enforceBudget(GenerationLane lane) {
        long budget = budget(lane);
        if (budget <= 0) return;
        try {
            String usedValue = redis.opsForValue().get(budgetKey(lane));
            long used = usedValue == null ? 0L : Long.parseLong(usedValue);
            if (used >= budget) throw new BusinessException(ErrorCode.AI_COST_BUDGET_EXHAUSTED);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AI_UPSTREAM_UNAVAILABLE, "成本预算服务暂时不可用");
        }
    }

    private String budgetKey(GenerationLane lane) {
        return "myoj:ai:cost:" + LocalDate.now(ZoneId.of("Asia/Shanghai")) + ":" + lane.name();
    }

    private long budget(GenerationLane lane) {
        return lane == GenerationLane.ADMIN_REVIEW ? reviewBudgetMicros : publicBudgetMicros;
    }

    public boolean isCircuitOpen() {
        return System.currentTimeMillis() < openUntil.get();
    }

    private int estimate(String text) {
        return text == null || text.isEmpty() ? 0 : Math.max(1, (text.length() + 2) / 3);
    }

    private int estimateOutput(Object output) {
        try {
            return estimate(objectMapper.writeValueAsString(output));
        } catch (Exception ignored) {
            return 0;
        }
    }

    public record ModelCallResult<T>(T value, Integer inputTokens, Integer outputTokens, String modelName) {
        public static <T> ModelCallResult<T> from(ChatResponse response, T value) {
            Usage usage = response == null || response.getMetadata() == null
                    ? null : response.getMetadata().getUsage();
            String model = response == null || response.getMetadata() == null
                    ? null : response.getMetadata().getModel();
            return new ModelCallResult<>(value,
                    usage == null ? null : usage.getPromptTokens(),
                    usage == null ? null : usage.getCompletionTokens(), model);
        }
    }
}

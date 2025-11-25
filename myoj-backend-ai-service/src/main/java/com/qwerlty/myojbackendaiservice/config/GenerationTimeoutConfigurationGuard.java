package com.qwerlty.myojbackendaiservice.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GenerationTimeoutConfigurationGuard {

    static final long MINIMUM_TASK_TIMEOUT_MS = 60_000L;
    static final long MINIMUM_RECOVERY_GRACE_MS = 120_000L;

    private final long problemDraftTimeoutMs;
    private final long testCasesTimeoutMs;
    private final long qualityReviewTimeoutMs;
    private final long runningTimeoutMs;

    public GenerationTimeoutConfigurationGuard(
            @Value("${myoj.ai.generation.workflow.problem-draft-timeout-ms:720000}") long problemDraftTimeoutMs,
            @Value("${myoj.ai.generation.workflow.test-cases-timeout-ms:1080000}") long testCasesTimeoutMs,
            @Value("${myoj.ai.generation.workflow.quality-review-timeout-ms:900000}") long qualityReviewTimeoutMs,
            @Value("${myoj.ai.generation.running-timeout-ms:1380000}") long runningTimeoutMs) {
        long maxWorkflowTimeoutMs = Math.max(problemDraftTimeoutMs,
                Math.max(testCasesTimeoutMs, qualityReviewTimeoutMs));
        if (problemDraftTimeoutMs < MINIMUM_TASK_TIMEOUT_MS
                || testCasesTimeoutMs < MINIMUM_TASK_TIMEOUT_MS
                || qualityReviewTimeoutMs < MINIMUM_TASK_TIMEOUT_MS) {
            throw new IllegalStateException("AI 出题任务超时时间不能小于 60 秒");
        }
        if (runningTimeoutMs <= maxWorkflowTimeoutMs
                || runningTimeoutMs - maxWorkflowTimeoutMs < MINIMUM_RECOVERY_GRACE_MS) {
            throw new IllegalStateException("AI 出题运行恢复阈值必须比任务超时至少晚 120 秒");
        }
        this.problemDraftTimeoutMs = problemDraftTimeoutMs;
        this.testCasesTimeoutMs = testCasesTimeoutMs;
        this.qualityReviewTimeoutMs = qualityReviewTimeoutMs;
        this.runningTimeoutMs = runningTimeoutMs;
    }

    @PostConstruct
    void logConfiguration() {
        long maxWorkflowTimeoutMs = Math.max(problemDraftTimeoutMs,
                Math.max(testCasesTimeoutMs, qualityReviewTimeoutMs));
        log.info("[AI_GENERATION] timeout configuration problemDraftMs={} testCasesMs={} qualityReviewMs={} runningTimeoutMs={} recoveryGraceMs={}",
                problemDraftTimeoutMs, testCasesTimeoutMs, qualityReviewTimeoutMs,
                runningTimeoutMs, runningTimeoutMs - maxWorkflowTimeoutMs);
    }
}

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

    private final long taskTimeoutMs;
    private final long runningTimeoutMs;

    public GenerationTimeoutConfigurationGuard(
            @Value("${myoj.ai.generation.task-timeout-ms:900000}") long taskTimeoutMs,
            @Value("${myoj.ai.generation.running-timeout-ms:1200000}") long runningTimeoutMs) {
        if (taskTimeoutMs < MINIMUM_TASK_TIMEOUT_MS) {
            throw new IllegalStateException("AI 出题任务超时时间不能小于 60 秒");
        }
        if (runningTimeoutMs <= taskTimeoutMs
                || runningTimeoutMs - taskTimeoutMs < MINIMUM_RECOVERY_GRACE_MS) {
            throw new IllegalStateException("AI 出题运行恢复阈值必须比任务超时至少晚 120 秒");
        }
        this.taskTimeoutMs = taskTimeoutMs;
        this.runningTimeoutMs = runningTimeoutMs;
    }

    @PostConstruct
    void logConfiguration() {
        log.info("[AI_GENERATION] timeout configuration taskTimeoutMs={} runningTimeoutMs={} recoveryGraceMs={}",
                taskTimeoutMs, runningTimeoutMs, runningTimeoutMs - taskTimeoutMs);
    }
}

package com.qwerlty.myojbackendaiservice.job;

import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStatus;
import com.qwerlty.myojbackendaiservice.queue.GenerationStreamConsumer;
import com.qwerlty.myojbackendaiservice.queue.GenerationStreamManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Slf4j
@Component
public class GenerationRecoveryJob {
    private final AiProblemGenerationTaskMapper taskMapper;
    private final GenerationStreamManager streamManager;
    private final GenerationStreamConsumer streamConsumer;
    private final int maxAttempts;
    private final long runningTimeoutMs;
    private final int batchSize;
    private final String recoveryConsumer;

    public GenerationRecoveryJob(
            AiProblemGenerationTaskMapper taskMapper,
            GenerationStreamManager streamManager,
            GenerationStreamConsumer streamConsumer,
            @Value("${myoj.ai.generation.max-attempts:3}") int maxAttempts,
            @Value("${myoj.ai.generation.running-timeout-ms:1200000}") long runningTimeoutMs,
            @Value("${myoj.ai.generation.stream.reclaim-batch-size:20}") int batchSize,
            @Value("${myoj.ai.generation.stream.consumer-prefix:${HOSTNAME:ai-generation}}") String prefix) {
        this.taskMapper = taskMapper;
        this.streamManager = streamManager;
        this.streamConsumer = streamConsumer;
        this.maxAttempts = maxAttempts;
        this.runningTimeoutMs = runningTimeoutMs;
        this.batchSize = batchSize;
        this.recoveryConsumer = prefix + "-recovery";
    }

    @Scheduled(fixedDelayString = "${myoj.ai.generation.recovery-interval-ms:15000}")
    public void recover() {
        requeuePending();
        try {
            List<MapRecord<String, String, String>> records = streamManager.claimStale(
                    recoveryConsumer, Duration.ofMillis(runningTimeoutMs), batchSize);
            if (!records.isEmpty()) {
                log.warn("[AI_GENERATION] stale stream records claimed count={} consumer={}",
                        records.size(), recoveryConsumer);
            }
            records.forEach(this::recoverRecord);
        } catch (RuntimeException exception) {
            log.warn("[AI_GENERATION] unable to reclaim stale stream records errorType={}",
                    exception.getClass().getSimpleName(), exception);
        }
    }

    private void requeuePending() {
        try {
            List<AiProblemGenerationTask> pendingTasks = taskMapper.listPending(batchSize);
            if (!pendingTasks.isEmpty()) {
                log.info("[AI_GENERATION] recovery found pending tasks count={}", pendingTasks.size());
            }
            for (AiProblemGenerationTask task : pendingTasks) {
                log.info("[AI_GENERATION] recovery requeueing pending task taskId={}", task.getId());
                streamManager.enqueue(task.getId());
            }
        } catch (RuntimeException exception) {
            log.warn("[AI_GENERATION] unable to requeue pending tasks errorType={}",
                    exception.getClass().getSimpleName(), exception);
        }
    }

    private void recoverRecord(MapRecord<String, String, String> record) {
        Long taskId;
        try {
            taskId = Long.valueOf(record.getValue().get("taskId"));
        } catch (RuntimeException exception) {
            streamConsumer.consume(record);
            return;
        }
        AiProblemGenerationTask task = taskMapper.selectById(taskId);
        if (task == null || task.getStatus() != GenerationStatus.RUNNING.getValue()) {
            streamConsumer.consume(record);
            return;
        }
        int attempts = task.getAttemptCount() == null ? 0 : task.getAttemptCount();
        if (attempts >= maxAttempts) {
            if (taskMapper.markTerminal(taskId, GenerationStatus.TIMED_OUT.getValue(),
                    "TASK_TIMEOUT", "AI 出题执行实例超时", runningTimeoutMs) > 0) {
                streamManager.acknowledgeAndDelete(record.getId());
                log.error("[AI_GENERATION] stale task marked timed out taskId={} attempts={}",
                        taskId, attempts);
            }
            return;
        }
        if (taskMapper.markRetry(taskId, "TASK_RECOVERED", "检测到执行实例中断，任务已重新排队") > 0) {
            log.warn("[AI_GENERATION] stale task reset for retry taskId={} attempts={}",
                    taskId, attempts);
            streamConsumer.consume(record);
        }
    }
}

package com.qwerlty.myojbackendaiservice.job;

import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStatus;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationLane;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.manager.GenerationAdmissionControl;
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
    private final GenerationAdmissionControl admissionControl;

    public GenerationRecoveryJob(
            AiProblemGenerationTaskMapper taskMapper,
            GenerationStreamManager streamManager,
            GenerationStreamConsumer streamConsumer,
            GenerationAdmissionControl admissionControl,
            @Value("${myoj.ai.generation.max-attempts:3}") int maxAttempts,
            @Value("${myoj.ai.generation.running-timeout-ms:1380000}") long runningTimeoutMs,
            @Value("${myoj.ai.generation.stream.reclaim-batch-size:20}") int batchSize,
            @Value("${myoj.ai.generation.stream.consumer-prefix:${HOSTNAME:ai-generation}}") String prefix) {
        this.taskMapper = taskMapper;
        this.streamManager = streamManager;
        this.streamConsumer = streamConsumer;
        this.admissionControl = admissionControl;
        this.maxAttempts = maxAttempts;
        this.runningTimeoutMs = runningTimeoutMs;
        this.batchSize = batchSize;
        this.recoveryConsumer = prefix + "-recovery";
    }

    @Scheduled(fixedDelayString = "${myoj.ai.generation.recovery-interval-ms:15000}")
    public void recover() {
        requeuePending();
        try {
            for (GenerationLane lane : GenerationLane.values()) {
                List<MapRecord<String, String, String>> records = streamManager.claimStale(
                        lane, recoveryConsumer + "-" + lane.name().toLowerCase(),
                        Duration.ofMillis(runningTimeoutMs), batchSize);
                if (!records.isEmpty()) {
                    log.warn("[AI_GENERATION] stale stream records claimed count={} consumer={} lane={}",
                            records.size(), recoveryConsumer, lane);
                }
                records.forEach(record -> recoverRecord(lane, record));
            }
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
                streamManager.enqueue(task.getId(), lane(task));
                taskMapper.deferPending(task.getId(), new java.util.Date(System.currentTimeMillis() + 30_000L));
            }
        } catch (RuntimeException exception) {
            log.warn("[AI_GENERATION] unable to requeue pending tasks errorType={}",
                    exception.getClass().getSimpleName(), exception);
        }
    }

    private void recoverRecord(GenerationLane lane, MapRecord<String, String, String> record) {
        Long taskId;
        try {
            taskId = Long.valueOf(record.getValue().get("taskId"));
        } catch (RuntimeException exception) {
            streamConsumer.consume(lane, record);
            return;
        }
        AiProblemGenerationTask task = taskMapper.selectById(taskId);
        if (task == null || task.getStatus() != GenerationStatus.RUNNING.getValue()) {
            streamConsumer.consume(lane, record);
            return;
        }
        if (Integer.valueOf(1).equals(task.getCancelRequested())) {
            if (taskMapper.markTerminal(taskId, GenerationStatus.CANCELLED.getValue(),
                    null, null, task.getStage(), runningTimeoutMs) > 0) {
                streamManager.acknowledgeAndDelete(lane, record.getId());
                AiProblemGenerationTask terminal = taskMapper.selectById(taskId);
                if (admissionControl.settle(terminal == null ? task : terminal, false)) {
                    taskMapper.updateQuotaStatus(taskId, "RESERVED", "SETTLED");
                }
                log.info("[AI_GENERATION] stale cancelled task settled without refund taskId={}", taskId);
            }
            return;
        }
        int attempts = task.getAttemptCount() == null ? 0 : task.getAttemptCount();
        if (attempts >= maxAttempts) {
            if (taskMapper.markTerminal(taskId, GenerationStatus.TIMED_OUT.getValue(),
                    "TASK_TIMEOUT", "AI 出题执行实例超时", task.getStage(), runningTimeoutMs) > 0) {
                streamManager.acknowledgeAndDelete(lane, record.getId());
                AiProblemGenerationTask terminal = taskMapper.selectById(taskId);
                if (admissionControl.settle(terminal == null ? task : terminal, true)) {
                    taskMapper.updateQuotaStatus(taskId, "RESERVED", "REFUNDED");
                }
                log.error("[AI_GENERATION] stale task marked timed out taskId={} attempts={}",
                        taskId, attempts);
            }
            return;
        }
        if (taskMapper.markRetry(taskId, "TASK_RECOVERED", "检测到执行实例中断，任务已重新排队") > 0) {
            admissionControl.revertStart(task);
            log.warn("[AI_GENERATION] stale task reset for retry taskId={} attempts={}",
                    taskId, attempts);
            streamConsumer.consume(lane, record);
        }
    }

    private GenerationLane lane(AiProblemGenerationTask task) {
        return task.getLane() == null
                ? GenerationLane.forType(AuthoringTaskType.parse(task.getMode()))
                : GenerationLane.valueOf(task.getLane());
    }
}

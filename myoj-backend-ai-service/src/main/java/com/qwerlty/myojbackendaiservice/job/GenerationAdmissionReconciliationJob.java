package com.qwerlty.myojbackendaiservice.job;

import com.qwerlty.myojbackendaiservice.manager.GenerationAdmissionControl;
import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Repairs the Redis/DB settlement edge when either side was unavailable at task completion. */
@Slf4j
@Component
public class GenerationAdmissionReconciliationJob {
    private static final Set<String> REFUNDABLE_SYSTEM_FAILURES = Set.of(
            "TASK_TIMEOUT", "MODEL_UNAVAILABLE", "MODEL_OUTPUT_INVALID",
            "DEPENDENCY_RATE_LIMITED", "DEPENDENCY_UNAVAILABLE", "DEPENDENCY_TIMEOUT",
            "WORKER_BUSY", "GENERATION_FAILED");

    private final AiProblemGenerationTaskMapper taskMapper;
    private final GenerationAdmissionControl admissionControl;
    private final int batchSize;

    public GenerationAdmissionReconciliationJob(
            AiProblemGenerationTaskMapper taskMapper,
            GenerationAdmissionControl admissionControl,
            @Value("${myoj.ai.generation.reconciliation-batch-size:100}") int batchSize) {
        this.taskMapper = taskMapper;
        this.admissionControl = admissionControl;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${myoj.ai.generation.reconciliation-interval-ms:30000}")
    public void reconcile() {
        for (AiProblemGenerationTask task : taskMapper.listUnsettledTerminalTasks(batchSize)) {
            boolean refund = shouldRefund(task);
            if (admissionControl.settle(task, refund)) {
                taskMapper.updateQuotaStatus(task.getId(), "RESERVED", refund ? "REFUNDED" : "SETTLED");
            } else {
                log.warn("[AI_GENERATION] admission reconciliation deferred taskId={}", task.getId());
            }
        }
    }

    private boolean shouldRefund(AiProblemGenerationTask task) {
        GenerationStatus status = GenerationStatus.fromValue(task.getStatus());
        if (status == GenerationStatus.TIMED_OUT) return true;
        if (status == GenerationStatus.CANCELLED) return task.getStartedTime() == null;
        return status == GenerationStatus.FAILED && REFUNDABLE_SYSTEM_FAILURES.contains(task.getErrorCode());
    }
}

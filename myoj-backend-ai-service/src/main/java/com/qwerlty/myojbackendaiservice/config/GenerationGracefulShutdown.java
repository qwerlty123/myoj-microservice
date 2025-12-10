package com.qwerlty.myojbackendaiservice.config;

import com.qwerlty.myojbackendaiservice.manager.GenerationAdmissionControl;
import com.qwerlty.myojbackendaiservice.manager.GenerationExecutionRegistry;
import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Stops intake first, then gives local workflows a bounded window to finish. */
@Slf4j
@Component
public class GenerationGracefulShutdown {
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private final ExecutorService publicExecutor;
    private final ExecutorService reviewExecutor;
    private final GenerationExecutionRegistry executions;
    private final AiProblemGenerationTaskMapper taskMapper;
    private final GenerationAdmissionControl admissionControl;
    private final long graceMs;

    public GenerationGracefulShutdown(
            @Qualifier("generationStreamListenerContainer")
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
            @Qualifier("problemGenerationPublicExecutor") ExecutorService publicExecutor,
            @Qualifier("problemGenerationReviewExecutor") ExecutorService reviewExecutor,
            GenerationExecutionRegistry executions,
            AiProblemGenerationTaskMapper taskMapper,
            GenerationAdmissionControl admissionControl,
            @Value("${myoj.ai.generation.shutdown-grace-ms:120000}") long graceMs) {
        this.container = container;
        this.publicExecutor = publicExecutor;
        this.reviewExecutor = reviewExecutor;
        this.executions = executions;
        this.taskMapper = taskMapper;
        this.admissionControl = admissionControl;
        this.graceMs = Math.max(1_000L, graceMs);
    }

    @PreDestroy
    public void shutdown() {
        log.info("[AI_GENERATION] graceful shutdown started graceMs={}", graceMs);
        container.stop();
        publicExecutor.shutdown();
        reviewExecutor.shutdown();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs);
        boolean publicStopped = await(publicExecutor, deadline);
        boolean reviewStopped = await(reviewExecutor, deadline);
        if (!publicStopped || !reviewStopped) {
            for (AiProblemGenerationTask task : executions.snapshot()) {
                if (taskMapper.markShutdownRecovery(task.getId(), task.getStage()) > 0) {
                    admissionControl.revertStart(task);
                    log.warn("[AI_GENERATION] task checkpoint handed to recovery during shutdown taskId={}",
                            task.getId());
                }
            }
            publicExecutor.shutdownNow();
            reviewExecutor.shutdownNow();
        }
        log.info("[AI_GENERATION] graceful shutdown completed localRunning={}", executions.snapshot().size());
    }

    private boolean await(ExecutorService executor, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) return executor.isTerminated();
        try {
            return executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

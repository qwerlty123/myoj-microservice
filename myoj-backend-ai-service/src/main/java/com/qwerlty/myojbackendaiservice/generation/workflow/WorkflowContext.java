package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.generation.GenerationProgressListener;
import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class WorkflowContext {
    private static final int CHECKPOINT_SCHEMA_VERSION = 1;

    private final Long taskId;
    private final Long userId;
    private final String role;
    private final String traceId;
    private final Long submissionId;
    private final long deadlineEpochMs;
    private final AuthoringTaskType taskType;
    private final String promptVersion;
    private final long timeoutMs;
    private final ObjectMapper objectMapper;
    private final GenerationProgressListener progressListener;
    private final WorkflowCheckpointStore checkpointStore;
    private final BooleanSupplier cancelled;
    private final MeterRegistry meterRegistry;
    private final List<ToolCallTrace> toolTrace = new ArrayList<>();
    private final Optional<WorkflowCheckpoint> resumeCheckpoint;
    private final ToolExecutionGuard toolExecutionGuard;

    public WorkflowContext(Long taskId,
                           AuthoringTaskType taskType,
                           String promptVersion,
                           long timeoutMs,
                           ObjectMapper objectMapper,
                           GenerationProgressListener progressListener,
                           WorkflowCheckpointStore checkpointStore,
                           BooleanSupplier cancelled,
                           MeterRegistry meterRegistry) {
        this.taskId = taskId;
        this.userId = 0L;
        this.role = "system";
        this.traceId = "legacy";
        this.submissionId = null;
        this.deadlineEpochMs = System.currentTimeMillis() + timeoutMs;
        this.taskType = taskType;
        this.promptVersion = promptVersion;
        this.timeoutMs = timeoutMs;
        this.objectMapper = objectMapper;
        this.progressListener = progressListener;
        this.checkpointStore = checkpointStore;
        this.cancelled = cancelled;
        this.meterRegistry = meterRegistry;
        this.toolExecutionGuard = ToolExecutionGuard.noop();
        Optional<WorkflowCheckpoint> loaded = initializeCheckpoint();
        this.resumeCheckpoint = loaded;
    }

    public WorkflowContext(Long taskId,
                           Long userId,
                           String role,
                           String traceId,
                           Long submissionId,
                           AuthoringTaskType taskType,
                           String promptVersion,
                           long timeoutMs,
                           ObjectMapper objectMapper,
                           GenerationProgressListener progressListener,
                           WorkflowCheckpointStore checkpointStore,
                           BooleanSupplier cancelled,
                           MeterRegistry meterRegistry,
                           ToolExecutionGuard toolExecutionGuard) {
        this.taskId = taskId;
        this.userId = userId;
        this.role = role;
        this.traceId = traceId;
        this.submissionId = submissionId;
        this.deadlineEpochMs = System.currentTimeMillis() + timeoutMs;
        this.taskType = taskType;
        this.promptVersion = promptVersion;
        this.timeoutMs = timeoutMs;
        this.objectMapper = objectMapper;
        this.progressListener = progressListener;
        this.checkpointStore = checkpointStore;
        this.cancelled = cancelled;
        this.meterRegistry = meterRegistry;
        this.toolExecutionGuard = toolExecutionGuard;
        this.resumeCheckpoint = initializeCheckpoint();
    }

    private Optional<WorkflowCheckpoint> initializeCheckpoint() {
        Optional<WorkflowCheckpoint> loaded = checkpointStore.load();
        Optional<WorkflowCheckpoint> compatible = loaded.filter(checkpoint -> checkpoint.schemaVersion() == CHECKPOINT_SCHEMA_VERSION)
                .filter(checkpoint -> Objects.equals(promptVersion, checkpoint.promptVersion()));
        if (loaded.isPresent() && compatible.isEmpty()) {
            checkpointStore.clear();
            meterRegistry.counter("ai_authoring_checkpoints_total",
                    "type", taskType.name(), "operation", "discard_incompatible").increment();
        }
        compatible.map(WorkflowCheckpoint::toolTrace).ifPresent(trace -> {
            if (trace != null) toolTrace.addAll(trace);
        });
        if (compatible.isPresent()) {
            meterRegistry.counter("ai_authoring_checkpoint_resumes_total", "type", taskType.name()).increment();
        }
        return compatible;
    }

    public static WorkflowContext testing(Long taskId) {
        return testing(taskId, AuthoringTaskType.PROBLEM_DRAFT);
    }

    public static WorkflowContext testing(Long taskId, AuthoringTaskType taskType) {
        return new WorkflowContext(taskId, taskType, "test-v1", 60_000L, new ObjectMapper(),
                stage -> { }, WorkflowCheckpointStore.noop(), () -> false, new SimpleMeterRegistry());
    }

    public Long taskId() { return taskId; }
    public Long userId() { return userId; }
    public String role() { return role; }
    public String traceId() { return traceId; }
    public Long submissionId() { return submissionId; }
    public long deadlineEpochMs() { return deadlineEpochMs; }
    public AuthoringTaskType taskType() { return taskType; }
    public long timeoutMs() { return timeoutMs; }
    public MeterRegistry meterRegistry() { return meterRegistry; }

    public void stage(GenerationStage stage) {
        checkCancelled();
        progressListener.onStage(stage);
        meterRegistry.counter("ai_authoring_stages_total",
                "type", taskType.name(), "stage", stage.name()).increment();
    }

    public <T> Optional<T> resume(Class<T> type) {
        return resumeCheckpoint
                .filter(checkpoint -> checkpoint.data() != null && !checkpoint.data().isNull())
                .map(checkpoint -> objectMapper.convertValue(checkpoint.data(), type));
    }

    public void checkpoint(GenerationStage stage, Object state) {
        checkCancelled();
        checkpointStore.save(new WorkflowCheckpoint(CHECKPOINT_SCHEMA_VERSION, promptVersion,
                stage.name(), objectMapper.valueToTree(state), List.copyOf(toolTrace)));
    }

    public void recordToolCall(ToolCallTrace trace) { toolTrace.add(trace); }
    public List<ToolCallTrace> toolTrace() { return Collections.unmodifiableList(toolTrace); }

    public void checkCancelled() {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new GenerationValidationException("AI 题目创作任务已取消");
        }
    }

    public void authorizeTool(String toolName) {
        checkCancelled();
        toolExecutionGuard.authorize(this, toolName);
    }
}

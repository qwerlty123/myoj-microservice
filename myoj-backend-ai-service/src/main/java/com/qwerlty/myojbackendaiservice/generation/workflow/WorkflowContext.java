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
        this.taskType = taskType;
        this.promptVersion = promptVersion;
        this.timeoutMs = timeoutMs;
        this.objectMapper = objectMapper;
        this.progressListener = progressListener;
        this.checkpointStore = checkpointStore;
        this.cancelled = cancelled;
        this.meterRegistry = meterRegistry;
        Optional<WorkflowCheckpoint> loaded = checkpointStore.load();
        this.resumeCheckpoint = loaded.filter(checkpoint -> checkpoint.schemaVersion() == CHECKPOINT_SCHEMA_VERSION)
                .filter(checkpoint -> Objects.equals(promptVersion, checkpoint.promptVersion()));
        if (loaded.isPresent() && resumeCheckpoint.isEmpty()) {
            checkpointStore.clear();
            meterRegistry.counter("ai_authoring_checkpoints_total",
                    "type", taskType.name(), "operation", "discard_incompatible").increment();
        }
        resumeCheckpoint.map(WorkflowCheckpoint::toolTrace).ifPresent(trace -> {
            if (trace != null) toolTrace.addAll(trace);
        });
        if (resumeCheckpoint.isPresent()) {
            meterRegistry.counter("ai_authoring_checkpoint_resumes_total", "type", taskType.name()).increment();
        }
    }

    public static WorkflowContext testing(Long taskId) {
        return new WorkflowContext(taskId, AuthoringTaskType.PROBLEM_DRAFT, "test-v1", 60_000L, new ObjectMapper(),
                stage -> { }, WorkflowCheckpointStore.noop(), () -> false, new SimpleMeterRegistry());
    }

    public Long taskId() { return taskId; }
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
}

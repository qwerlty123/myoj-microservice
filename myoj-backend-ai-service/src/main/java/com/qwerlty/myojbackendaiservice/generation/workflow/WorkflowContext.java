package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.generation.GenerationProgressListener;
import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

public final class WorkflowContext {
    private static final int CHECKPOINT_SCHEMA_VERSION = 1;

    private final Long taskId;
    private final String promptVersion;
    private final long timeoutMs;
    private final ObjectMapper objectMapper;
    private final GenerationProgressListener progressListener;
    private final WorkflowCheckpointStore checkpointStore;
    private final BooleanSupplier cancelled;
    private final MeterRegistry meterRegistry;
    private final List<ToolCallTrace> toolTrace = new ArrayList<>();

    public WorkflowContext(Long taskId,
                           String promptVersion,
                           long timeoutMs,
                           ObjectMapper objectMapper,
                           GenerationProgressListener progressListener,
                           WorkflowCheckpointStore checkpointStore,
                           BooleanSupplier cancelled,
                           MeterRegistry meterRegistry) {
        this.taskId = taskId;
        this.promptVersion = promptVersion;
        this.timeoutMs = timeoutMs;
        this.objectMapper = objectMapper;
        this.progressListener = progressListener;
        this.checkpointStore = checkpointStore;
        this.cancelled = cancelled;
        this.meterRegistry = meterRegistry;
        checkpointStore.load().map(WorkflowCheckpoint::toolTrace).ifPresent(trace -> {
            if (trace != null) toolTrace.addAll(trace);
        });
    }

    public static WorkflowContext testing(Long taskId) {
        return new WorkflowContext(taskId, "test-v1", 60_000L, new ObjectMapper(),
                stage -> { }, WorkflowCheckpointStore.noop(), () -> false, new SimpleMeterRegistry());
    }

    public Long taskId() { return taskId; }
    public long timeoutMs() { return timeoutMs; }
    public MeterRegistry meterRegistry() { return meterRegistry; }

    public void stage(GenerationStage stage) {
        checkCancelled();
        progressListener.onStage(stage);
    }

    public <T> Optional<T> resume(Class<T> type) {
        return checkpointStore.load()
                .filter(checkpoint -> checkpoint.schemaVersion() == CHECKPOINT_SCHEMA_VERSION)
                .filter(checkpoint -> promptVersion.equals(checkpoint.promptVersion()))
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

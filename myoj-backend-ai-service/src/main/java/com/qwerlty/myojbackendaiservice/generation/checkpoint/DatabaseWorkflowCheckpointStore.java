package com.qwerlty.myojbackendaiservice.generation.checkpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.generation.workflow.WorkflowCheckpoint;
import com.qwerlty.myojbackendaiservice.generation.workflow.WorkflowCheckpointStore;
import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStage;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Optional;

/** MySQL adapter for the workflow checkpoint seam. */
public final class DatabaseWorkflowCheckpointStore implements WorkflowCheckpointStore {
    private final Long taskId;
    private final AuthoringTaskType taskType;
    private final AiProblemGenerationTaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public DatabaseWorkflowCheckpointStore(Long taskId,
                                           AuthoringTaskType taskType,
                                           AiProblemGenerationTaskMapper taskMapper,
                                           ObjectMapper objectMapper,
                                           MeterRegistry meterRegistry) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Optional<WorkflowCheckpoint> load() {
        AiProblemGenerationTask task = taskMapper.selectById(taskId);
        if (task == null || task.getWorkflowStateJson() == null || task.getWorkflowStateJson().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(task.getWorkflowStateJson(), WorkflowCheckpoint.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI 题目创作断点无法解析", exception);
        }
    }

    @Override
    public void save(WorkflowCheckpoint checkpoint) {
        GenerationStage stage;
        try {
            stage = GenerationStage.valueOf(checkpoint.completedStage());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("未知的工作流断点阶段: " + checkpoint.completedStage(), exception);
        }
        try {
            String json = objectMapper.writeValueAsString(checkpoint);
            if (taskMapper.updateCheckpoint(taskId, json, stage.name(), stage.getProgress()) <= 0) {
                throw new IllegalStateException("AI 题目创作任务状态已变化，断点未保存");
            }
            meterRegistry.counter("ai_authoring_checkpoints_total",
                    "type", taskType.name(), "operation", "save").increment();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI 题目创作断点无法序列化", exception);
        }
    }

    @Override
    public void clear() {
        taskMapper.clearCheckpoint(taskId);
        meterRegistry.counter("ai_authoring_checkpoints_total",
                "type", taskType.name(), "operation", "clear").increment();
    }
}

package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Tool authorization is derived from persisted task context, never from model arguments. */
@Component
public class ToolExecutionGuard {
    private static final Map<AuthoringTaskType, Set<String>> ALLOWLIST = Map.of(
            AuthoringTaskType.PROBLEM_DRAFT, Set.of("verifyDraftPatch"),
            AuthoringTaskType.TEST_CASES, Set.of("evaluateCandidateCases", "searchAuthoringKnowledge"),
            AuthoringTaskType.QUALITY_REVIEW, Set.of("inspectCaseEvidence", "searchAuthoringKnowledge"));

    private final AiProblemGenerationTaskMapper taskMapper;

    public ToolExecutionGuard(AiProblemGenerationTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    public void authorize(WorkflowContext context, String toolName) {
        if (!ALLOWLIST.getOrDefault(context.taskType(), Set.of()).contains(toolName)) {
            throw new GenerationValidationException("当前工作流无权调用工具: " + toolName);
        }
        AiProblemGenerationTask task = taskMapper.selectById(context.taskId());
        if (task == null || task.getStatus() != GenerationStatus.RUNNING.getValue()
                || Integer.valueOf(1).equals(task.getCancelRequested())
                || !context.userId().equals(task.getUserId())
                || !context.taskType().name().equals(task.getMode())) {
            throw new GenerationValidationException("工具调用上下文已失效");
        }
    }

    public static ToolExecutionGuard noop() {
        return new ToolExecutionGuard(null) {
            @Override public void authorize(WorkflowContext context, String toolName) {
                if (!ALLOWLIST.getOrDefault(context.taskType(), Set.of()).contains(toolName)) {
                    throw new GenerationValidationException("当前工作流无权调用工具: " + toolName);
                }
            }
        };
    }
}

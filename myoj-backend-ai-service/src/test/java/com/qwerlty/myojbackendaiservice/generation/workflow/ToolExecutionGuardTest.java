package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolExecutionGuardTest {
    @Test
    void permitsOnlyThePersistedRunningTaskAllowlist() {
        AiProblemGenerationTaskMapper mapper = mock(AiProblemGenerationTaskMapper.class);
        ToolExecutionGuard guard = new ToolExecutionGuard(mapper);
        AiProblemGenerationTask task = task(8L, 7L, AuthoringTaskType.TEST_CASES, GenerationStatus.RUNNING);
        when(mapper.selectById(8L)).thenReturn(task);
        WorkflowContext context = context(guard, AuthoringTaskType.TEST_CASES);

        assertThatCode(() -> guard.authorize(context, "evaluateCandidateCases")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.authorize(context, "inspectCaseEvidence"))
                .isInstanceOf(GenerationValidationException.class).hasMessageContaining("无权");

        task.setStatus(GenerationStatus.CANCELLED.getValue());
        assertThatThrownBy(() -> guard.authorize(context, "evaluateCandidateCases"))
                .isInstanceOf(GenerationValidationException.class).hasMessageContaining("失效");
    }

    private WorkflowContext context(ToolExecutionGuard guard, AuthoringTaskType type) {
        return new WorkflowContext(8L, 7L, "user", "trace", null, type, "test-v1", 60_000L,
                new ObjectMapper(), stage -> { }, WorkflowCheckpointStore.noop(), () -> false,
                new SimpleMeterRegistry(), guard);
    }

    private AiProblemGenerationTask task(Long id, Long userId, AuthoringTaskType type,
                                          GenerationStatus status) {
        AiProblemGenerationTask task = new AiProblemGenerationTask();
        task.setId(id);
        task.setUserId(userId);
        task.setMode(type.name());
        task.setStatus(status.getValue());
        task.setCancelRequested(0);
        return task;
    }
}

package com.qwerlty.myojbackendaiservice.generation.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.generation.workflow.WorkflowCheckpoint;
import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseWorkflowCheckpointStoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiProblemGenerationTaskMapper mapper = mock(AiProblemGenerationTaskMapper.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void savesStageStateAndProgressAtomically() {
        DatabaseWorkflowCheckpointStore store = store();
        WorkflowCheckpoint checkpoint = new WorkflowCheckpoint(1, "authoring-v2",
                GenerationStage.PLANNING_COVERAGE.name(), objectMapper.valueToTree(List.of("boundary")), List.of());
        when(mapper.updateCheckpoint(42L, objectMapper.valueToTree(checkpoint).toString(),
                GenerationStage.PLANNING_COVERAGE.name(), GenerationStage.PLANNING_COVERAGE.getProgress()))
                .thenReturn(1);

        store.save(checkpoint);

        verify(mapper).updateCheckpoint(42L, objectMapper.valueToTree(checkpoint).toString(),
                GenerationStage.PLANNING_COVERAGE.name(), GenerationStage.PLANNING_COVERAGE.getProgress());
        assertThat(meterRegistry.counter("ai_authoring_checkpoints_total",
                "type", "TEST_CASES", "operation", "save").count()).isEqualTo(1);
    }

    @Test
    void loadsPersistedCheckpoint() throws Exception {
        WorkflowCheckpoint checkpoint = new WorkflowCheckpoint(1, "authoring-v2",
                GenerationStage.ANALYZING_SOURCE.name(), objectMapper.valueToTree("ready"), List.of());
        AiProblemGenerationTask task = new AiProblemGenerationTask();
        task.setWorkflowStateJson(objectMapper.writeValueAsString(checkpoint));
        when(mapper.selectById(42L)).thenReturn(task);

        assertThat(store().load()).contains(checkpoint);
    }

    @Test
    void clearsLargeIntermediateState() {
        store().clear();

        verify(mapper).clearCheckpoint(42L);
    }

    private DatabaseWorkflowCheckpointStore store() {
        return new DatabaseWorkflowCheckpointStore(42L, AuthoringTaskType.TEST_CASES,
                mapper, objectMapper, meterRegistry);
    }
}

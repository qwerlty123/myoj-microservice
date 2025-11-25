package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowContextTest {

    @Test
    void incompatibleCheckpointIsClearedAndNotResumed() {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkflowCheckpointStore store = mock(WorkflowCheckpointStore.class);
        when(store.load()).thenReturn(Optional.of(new WorkflowCheckpoint(
                1, "old-prompt", "DRAFTING_SPEC", objectMapper.valueToTree("stale"), java.util.List.of())));

        WorkflowContext context = new WorkflowContext(7L, AuthoringTaskType.PROBLEM_DRAFT,
                "authoring-v2", 60_000L, objectMapper, stage -> { }, store, () -> false,
                new SimpleMeterRegistry());

        assertThat(context.resume(String.class)).isEmpty();
        verify(store).clear();
    }

    @Test
    void compatibleCheckpointIsLoadedOnlyOnceAndResumed() {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkflowCheckpointStore store = mock(WorkflowCheckpointStore.class);
        when(store.load()).thenReturn(Optional.of(new WorkflowCheckpoint(
                1, "authoring-v2", "DRAFTING_SPEC", objectMapper.valueToTree("ready"), java.util.List.of())));

        WorkflowContext context = new WorkflowContext(7L, AuthoringTaskType.PROBLEM_DRAFT,
                "authoring-v2", 60_000L, objectMapper, stage -> { }, store, () -> false,
                new SimpleMeterRegistry());

        assertThat(context.resume(String.class)).contains("ready");
        verify(store).load();
    }
}

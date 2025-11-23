package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoringWorkflowRegistryTest {

    @Test
    void dispatchesThroughTheSingleWorkflowRegisteredForAType() {
        StubWorkflow workflow = new StubWorkflow(AuthoringTaskType.PROBLEM_DRAFT);
        AuthoringWorkflowRegistry registry = new AuthoringWorkflowRegistry(List.of(workflow));

        StubArtifact result = registry.execute(
                AuthoringTaskType.PROBLEM_DRAFT,
                WorkflowContext.testing(42L),
                new StubRequest("topic"));

        assertThat(result.value()).isEqualTo("handled:topic");
    }

    @Test
    void rejectsDuplicateWorkflowTypesAtStartup() {
        assertThatThrownBy(() -> new AuthoringWorkflowRegistry(List.of(
                new StubWorkflow(AuthoringTaskType.TEST_CASES),
                new StubWorkflow(AuthoringTaskType.TEST_CASES))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TEST_CASES");
    }

    @Test
    void rejectsAnUnknownTaskTypeInsteadOfFallingBack() {
        AuthoringWorkflowRegistry registry = new AuthoringWorkflowRegistry(List.of(
                new StubWorkflow(AuthoringTaskType.PROBLEM_DRAFT)));

        assertThatThrownBy(() -> registry.execute(
                AuthoringTaskType.QUALITY_REVIEW,
                WorkflowContext.testing(42L),
                new StubRequest("draft")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QUALITY_REVIEW");
    }

    private record StubRequest(String value) implements AuthoringRequest {
    }

    private record StubArtifact(String value) implements AuthoringArtifact {
    }

    private record StubWorkflow(AuthoringTaskType type)
            implements AuthoringWorkflow<StubRequest, StubArtifact> {
        @Override
        public Class<StubRequest> requestType() {
            return StubRequest.class;
        }

        @Override
        public StubArtifact execute(WorkflowContext context, StubRequest request) {
            return new StubArtifact("handled:" + request.value());
        }
    }
}

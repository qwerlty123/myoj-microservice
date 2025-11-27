package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CaseEvidence;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthoringToolLimitsTest {

    @Test
    void testCaseToolEnforcesBatchAndRoundBudgets() {
        SandboxBatchVerifier verifier = mock(SandboxBatchVerifier.class);
        when(verifier.verify(anyList(), anyList(), any(ValidationPrograms.class), any()))
                .thenReturn(new BatchVerificationResult(List.of(), List.of(), 0));
        TestCaseGenerationState state = new TestCaseGenerationState();
        state.setSpecification(new GeneratedProblemSpec());
        state.setPrograms(new ValidationPrograms());
        TestCaseAgentTools tools = new TestCaseAgentTools(WorkflowContext.testing(1L), verifier, state, 50);

        assertThatThrownBy(() -> tools.evaluateCandidateCases(java.util.stream.IntStream.range(0, 11)
                .mapToObj(index -> candidate("batch-" + index)).toList()))
                .isInstanceOf(GenerationValidationException.class)
                .hasMessageContaining("1 到 10");

        for (int round = 0; round < 8; round++) {
            tools.evaluateCandidateCases(List.of(candidate("round-" + round)));
        }
        assertThatThrownBy(() -> tools.evaluateCandidateCases(List.of(candidate("ninth"))))
                .isInstanceOf(GenerationValidationException.class)
                .hasMessageContaining("8 轮");
    }

    @Test
    void qualityEvidenceToolEnforcesFiveCasesAndThreeCalls() {
        CaseEvidence evidence = new CaseEvidence();
        QualityEvidenceTools tools = new QualityEvidenceTools(indexes -> List.of(evidence));

        assertThatThrownBy(() -> tools.inspectCaseEvidence(List.of(0, 1, 2, 3, 4, 5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 到 5");
        tools.inspectCaseEvidence(List.of(0));
        tools.inspectCaseEvidence(List.of(0));
        tools.inspectCaseEvidence(List.of(0));
        assertThatThrownBy(() -> tools.inspectCaseEvidence(List.of(0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3 次");
    }

    private CandidateTestInput candidate(String input) {
        CandidateTestInput candidate = new CandidateTestInput();
        candidate.setInput(input);
        candidate.setCategory("NORMAL");
        candidate.setOracleEligible(true);
        return candidate;
    }
}

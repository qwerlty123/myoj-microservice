package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateInputChunk;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CaseEvidence;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthoringToolLimitsTest {

    @Test
    void testCaseToolExpandsCompactRangeBeforeSandboxVerification() {
        SandboxBatchVerifier verifier = mock(SandboxBatchVerifier.class);
        AtomicReference<String> verifiedInput = new AtomicReference<>();
        when(verifier.verify(anyList(), anyList(), any(ValidationPrograms.class), any()))
                .thenAnswer(invocation -> {
                    List<CandidateTestInput> candidates = invocation.getArgument(0);
                    verifiedInput.set(candidates.get(0).getInput());
                    return new BatchVerificationResult(List.of(), List.of(), 0);
                });
        TestCaseGenerationState state = new TestCaseGenerationState();
        state.setSpecification(new GeneratedProblemSpec());
        state.setPrograms(new ValidationPrograms());
        TestCaseAgentTools tools = new TestCaseAgentTools(WorkflowContext.testing(1L), verifier, state, 10);

        CandidateTestInput candidate = new CandidateTestInput();
        candidate.setCategory("MAXIMUM");
        candidate.setChunks(List.of(
                CandidateInputChunk.literal("5\n"),
                CandidateInputChunk.range(1, 1, 5, " "),
                CandidateInputChunk.literal("\n")));

        tools.evaluateCandidateCases(List.of(candidate));

        assertThat(verifiedInput).hasValue("5\n1 2 3 4 5\n");
    }

    @Test
    void testCaseToolRejectsCompactInputThatExpandsPastOneMebibyte() {
        SandboxBatchVerifier verifier = mock(SandboxBatchVerifier.class);
        AtomicReference<Integer> verifiedCandidates = new AtomicReference<>();
        when(verifier.verify(anyList(), anyList(), any(ValidationPrograms.class), any()))
                .thenAnswer(invocation -> {
                    List<CandidateTestInput> candidates = invocation.getArgument(0);
                    verifiedCandidates.set(candidates.size());
                    return new BatchVerificationResult(List.of(), List.of(), 0);
                });
        TestCaseGenerationState state = new TestCaseGenerationState();
        state.setSpecification(new GeneratedProblemSpec());
        state.setPrograms(new ValidationPrograms());
        TestCaseAgentTools tools = new TestCaseAgentTools(WorkflowContext.testing(1L), verifier, state, 10);
        CandidateInputChunk repeated = new CandidateInputChunk();
        repeated.setType("REPEAT");
        repeated.setValue("x".repeat(2048));
        repeated.setCount(600);
        CandidateTestInput candidate = new CandidateTestInput();
        candidate.setChunks(List.of(repeated));
        candidate.setCategory("MAXIMUM");

        var result = tools.evaluateCandidateCases(List.of(candidate));

        assertThat(verifiedCandidates).hasValue(0);
        assertThat(result.getRejections()).singleElement()
                .satisfies(rejection -> {
                    assertThat(rejection.inputDigest()).isEqualTo("oversize");
                    assertThat(rejection.reason()).contains("1 MiB");
                });
    }

    @Test
    void testCaseToolReopensSlotsInLegacyCheckpointWithOnlyNormalCases() {
        SandboxBatchVerifier verifier = mock(SandboxBatchVerifier.class);
        TestCaseGenerationState state = new TestCaseGenerationState();
        state.setSpecification(new GeneratedProblemSpec());
        state.setPrograms(new ValidationPrograms());
        for (int index = 0; index < 10; index++) {
            state.getAcceptedCases().add(new AcceptedCaseState(
                    candidate("normal-" + index), "output", new CaseEvidence()));
        }
        TestCaseAgentTools tools = new TestCaseAgentTools(WorkflowContext.testing(1L), verifier, state, 10);

        int removed = tools.reopenSlotsForMissingCategories();

        assertThat(removed).isEqualTo(3);
        assertThat(state.getAcceptedCases()).hasSize(7);
        assertThat(tools.missingRequiredCategories())
                .containsExactly("BOUNDARY", "MAXIMUM", "ADVERSARIAL");
    }

    @Test
    void testCaseToolRejectsUnknownCategoryInsteadOfSilentlyTreatingItAsNormal() {
        SandboxBatchVerifier verifier = mock(SandboxBatchVerifier.class);
        when(verifier.verify(anyList(), anyList(), any(ValidationPrograms.class), any()))
                .thenAnswer(invocation -> {
                    List<CandidateTestInput> candidates = invocation.getArgument(0);
                    return new BatchVerificationResult(candidates.stream()
                            .map(candidate -> new VerifiedCandidate(candidate, "output", new CaseEvidence()))
                            .toList(), List.of(), 0);
                });
        TestCaseGenerationState state = new TestCaseGenerationState();
        state.setSpecification(new GeneratedProblemSpec());
        state.setPrograms(new ValidationPrograms());
        TestCaseAgentTools tools = new TestCaseAgentTools(WorkflowContext.testing(1L), verifier, state, 10);
        CandidateTestInput candidate = candidate("unknown-category");
        candidate.setCategory("边界");

        var result = tools.evaluateCandidateCases(List.of(candidate));

        assertThat(result.getAccepted()).isZero();
        assertThat(result.getRejections()).singleElement()
                .satisfies(rejection -> {
                    assertThat(rejection.inputDigest()).isEqualTo("category");
                    assertThat(rejection.reason()).contains("BOUNDARY", "MAXIMUM", "ADVERSARIAL");
                });
    }

    @Test
    void testCaseToolRecordsFailedSandboxRoundBeforeRethrowing() {
        SandboxBatchVerifier verifier = mock(SandboxBatchVerifier.class);
        when(verifier.verify(anyList(), anyList(), any(ValidationPrograms.class), any()))
                .thenThrow(new GenerationValidationException("sandbox verification failed"));
        TestCaseGenerationState state = new TestCaseGenerationState();
        state.setSpecification(new GeneratedProblemSpec());
        state.setPrograms(new ValidationPrograms());
        WorkflowContext context = WorkflowContext.testing(1L);
        TestCaseAgentTools tools = new TestCaseAgentTools(context, verifier, state, 10);

        assertThatThrownBy(() -> tools.evaluateCandidateCases(List.of(candidate("sandbox-error"))))
                .isInstanceOf(GenerationValidationException.class)
                .hasMessageContaining("sandbox verification failed");

        assertThat(context.toolTrace()).singleElement()
                .satisfies(trace -> {
                    assertThat(trace.round()).isEqualTo(1);
                    assertThat(trace.outcome()).isEqualTo("TOOL_ERROR");
                    assertThat(trace.errorType()).isEqualTo("GenerationValidationException");
                });
    }

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

package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.generation.sandbox.AuthoringSandboxVerifier;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationOutcome;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationReport;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationRequest;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationIssue;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationIssueCode;
import com.qwerlty.myojbackendaiservice.model.dto.generation.DraftRepairOperation;
import com.qwerlty.myojbackendaiservice.model.dto.generation.DraftRepairPatch;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProblemDraftToolsTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void specificationPatchInvalidatesEveryDownstreamArtifact() {
        ProblemDraftWorkflow.DraftState state = state();
        AuthoringSandboxVerifier verifier = mock(AuthoringSandboxVerifier.class);
        ProblemDraftTools tools = tools(state, verifier);

        var result = tools.verifyDraftPatch(patch(tools.currentStateHash(), "/spec/title", "修订题目"));

        assertThat(result.status()).isEqualTo("REGENERATION_REQUIRED");
        assertThat(state.getSpecification().getTitle()).isEqualTo("修订题目");
        assertThat(state.getSolutions()).isEmpty();
        assertThat(state.getPrograms()).isNull();
        assertThat(state.getSpecificationRepairCalls()).isEqualTo(1);
    }

    @Test
    void codePatchPreservesOtherArtifactsAndReturnsStructuredVerification() {
        ProblemDraftWorkflow.DraftState state = state();
        AuthoringSandboxVerifier verifier = mock(AuthoringSandboxVerifier.class);
        when(verifier.verify(any(VerificationRequest.class))).thenReturn(passed());
        ProblemDraftTools tools = tools(state, verifier);

        var result = tools.verifyDraftPatch(patch(tools.currentStateHash(), "/solutions/cpp", "cpp-fixed"));

        assertThat(result.status()).isEqualTo("PASSED");
        assertThat(state.getSolutions()).extracting(ReferenceSolution::getCode)
                .containsExactlyInAnyOrder("java-code", "go-code", "cpp-fixed");
        assertThat(state.getPrograms().getValidatorJava()).isEqualTo("validator");
    }

    @Test
    void rejectsStaleUnknownAndOversizedPatchesWithoutMutatingState() {
        ProblemDraftWorkflow.DraftState state = state();
        ProblemDraftTools tools = tools(state, mock(AuthoringSandboxVerifier.class));

        assertThatThrownBy(() -> tools.verifyDraftPatch(patch("stale", "/solutions/cpp", "fixed")))
                .isInstanceOf(GenerationValidationException.class).hasMessageContaining("过期");
        assertThatThrownBy(() -> tools.verifyDraftPatch(
                patch(tools.currentStateHash(), "/unknown", "fixed")))
                .isInstanceOf(GenerationValidationException.class).hasMessageContaining("不允许");
        assertThatThrownBy(() -> tools.verifyDraftPatch(patch(tools.currentStateHash(),
                "/spec/judgeConfig", Map.of("timeLimit", 1000, "extra", true))))
                .isInstanceOf(GenerationValidationException.class).hasMessageContaining("未知字段");
        assertThatThrownBy(() -> tools.verifyDraftPatch(
                patch(tools.currentStateHash(), "/solutions/cpp", "x".repeat(513 * 1024))))
                .isInstanceOf(GenerationValidationException.class).hasMessageContaining("512 KiB");
        assertThat(state.getRepairCalls()).isZero();
        assertThat(state.getSolutions()).extracting(ReferenceSolution::getCode).contains("cpp-code");
    }

    @Test
    void dependencyFailureKeepsThePatchButDoesNotConsumeRepairBudget() {
        ProblemDraftWorkflow.DraftState state = state();
        AuthoringSandboxVerifier verifier = mock(AuthoringSandboxVerifier.class);
        when(verifier.verify(any(VerificationRequest.class)))
                .thenThrow(new ResourceAccessException("sandbox unavailable"));
        ProblemDraftTools tools = tools(state, verifier);

        assertThatThrownBy(() -> tools.verifyDraftPatch(
                patch(tools.currentStateHash(), "/solutions/cpp", "cpp-fixed")))
                .isInstanceOf(ResourceAccessException.class);

        assertThat(state.getRepairCalls()).isZero();
        assertThat(state.getSolutions()).extracting(ReferenceSolution::getCode).contains("cpp-fixed");
    }

    @Test
    void sandboxCapacityFailureKeepsThePatchButDoesNotConsumeRepairBudget() {
        ProblemDraftWorkflow.DraftState state = state();
        AuthoringSandboxVerifier verifier = mock(AuthoringSandboxVerifier.class);
        when(verifier.verify(any(VerificationRequest.class)))
                .thenThrow(new RejectedExecutionException("sandbox busy"));
        ProblemDraftTools tools = tools(state, verifier);

        assertThatThrownBy(() -> tools.verifyDraftPatch(
                patch(tools.currentStateHash(), "/solutions/cpp", "cpp-fixed")))
                .isInstanceOf(RejectedExecutionException.class);

        assertThat(state.getRepairCalls()).isZero();
        assertThat(state.getSolutions()).extracting(ReferenceSolution::getCode).contains("cpp-fixed");
    }

    @Test
    void enforcesThreeCalls() {
        ProblemDraftWorkflow.DraftState state = state();
        AuthoringSandboxVerifier verifier = mock(AuthoringSandboxVerifier.class);
        VerificationIssue issue = new VerificationIssue(VerificationIssueCode.COMPILE_ERROR,
                "/solutions/cpp", "cpp", null, null, "compile", "diagnostic");
        when(verifier.verify(any(VerificationRequest.class))).thenReturn(new VerificationReport(
                VerificationOutcome.REPAIRABLE, List.of(), List.of(), 0, List.of(issue)));
        ProblemDraftTools tools = tools(state, verifier);

        for (int call = 0; call < 3; call++) {
            tools.verifyDraftPatch(patch(tools.currentStateHash(), "/solutions/cpp", "cpp-" + call));
        }

        assertThat(tools.hasRemainingCalls()).isFalse();
        assertThatThrownBy(() -> tools.verifyDraftPatch(
                patch(tools.currentStateHash(), "/solutions/cpp", "cpp-final")))
                .isInstanceOf(GenerationValidationException.class).hasMessageContaining("3 次");
    }

    @Test
    void permitsOnlyOneSpecificationPatchEvenAfterRegeneration() {
        ProblemDraftWorkflow.DraftState state = state();
        AuthoringSandboxVerifier verifier = mock(AuthoringSandboxVerifier.class);
        ProblemDraftTools tools = tools(state, verifier);
        tools.verifyDraftPatch(patch(tools.currentStateHash(), "/spec/title", "第一次修订"));
        state.setSolutions(List.of(solution("java"), solution("cpp"), solution("go")));
        ValidationPrograms programs = new ValidationPrograms();
        programs.setValidatorJava("validator");
        programs.setOracleJava("oracle");
        state.setPrograms(programs);
        ProblemDraftTools resumedTools = tools(state, verifier);

        assertThatThrownBy(() -> resumedTools.verifyDraftPatch(
                patch(resumedTools.currentStateHash(), "/spec/title", "第二次修订")))
                .isInstanceOf(GenerationValidationException.class).hasMessageContaining("最多允许修复一次");
    }

    private ProblemDraftTools tools(ProblemDraftWorkflow.DraftState state,
                                    AuthoringSandboxVerifier verifier) {
        return new ProblemDraftTools(WorkflowContext.testing(1L), verifier, state, objectMapper);
    }

    private ProblemDraftWorkflow.DraftState state() {
        ProblemDraftWorkflow.DraftState state = new ProblemDraftWorkflow.DraftState();
        GeneratedProblemSpec spec = new GeneratedProblemSpec();
        spec.setTitle("题目");
        spec.setContent("## 题目描述\n描述");
        spec.setSolutionExplanation("题解");
        spec.setDifficulty(1);
        spec.setTags(List.of("array"));
        spec.setJudgeConfig(new JudgeConfigValue());
        for (String input : List.of("1", "2")) {
            GeneratedTestInput sample = new GeneratedTestInput();
            sample.setInput(input);
            sample.setCategory("NORMAL");
            spec.getSampleInputs().add(sample);
        }
        state.setSpecification(spec);
        state.setSolutions(List.of(solution("java"), solution("cpp"), solution("go")));
        ValidationPrograms programs = new ValidationPrograms();
        programs.setValidatorJava("validator");
        programs.setOracleJava("oracle");
        state.setPrograms(programs);
        return state;
    }

    private ReferenceSolution solution(String language) {
        ReferenceSolution solution = new ReferenceSolution();
        solution.setLanguage(language);
        solution.setCode(language + "-code");
        return solution;
    }

    private DraftRepairPatch patch(String baseHash, String target, Object afterValue) {
        DraftRepairOperation operation = new DraftRepairOperation();
        operation.setTarget(target);
        operation.setAfterValue(afterValue);
        operation.setReason("repair");
        DraftRepairPatch patch = new DraftRepairPatch();
        patch.setBaseHash(baseHash);
        patch.setOperations(List.of(operation));
        return patch;
    }

    private VerificationReport passed() {
        return new VerificationReport(VerificationOutcome.PASSED, List.of(), List.of(), 0, List.of());
    }
}

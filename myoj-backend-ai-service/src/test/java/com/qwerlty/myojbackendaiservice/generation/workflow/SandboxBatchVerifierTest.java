package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import com.qwerlty.myojbackendaiservice.sandbox.CodeSandboxClient;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecuteResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SandboxBatchVerifierTest {

    @Test
    void rejectsCrossLanguageOutputMismatch() {
        CodeSandboxClient sandbox = mock(CodeSandboxClient.class);
        when(sandbox.execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong()))
                .thenReturn(success("VALID"), success("java"), success("cpp"), success("java"));

        BatchVerificationResult result = verifier(sandbox).verify(List.of(candidate()),
                solutions(), programs(), new JudgeConfigValue());

        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected()).singleElement().satisfies(rejection ->
                assertThat(rejection.reason()).contains("多语言校验解输出不一致"));
    }

    @Test
    void rejectsOracleMismatchEvenWhenJavaAndCppAgree() {
        CodeSandboxClient sandbox = mock(CodeSandboxClient.class);
        when(sandbox.execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong()))
                .thenReturn(success("VALID"), success("same"), success("same"), success("oracle"));

        BatchVerificationResult result = verifier(sandbox).verify(List.of(candidate()),
                solutions(), programs(), new JudgeConfigValue());

        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected()).singleElement().satisfies(rejection ->
                assertThat(rejection.reason()).contains("Oracle 输出不一致"));
    }

    private SandboxBatchVerifier verifier(CodeSandboxClient sandbox) {
        return new SandboxBatchVerifier(sandbox);
    }

    private CandidateTestInput candidate() {
        CandidateTestInput candidate = new CandidateTestInput();
        candidate.setInput("1");
        candidate.setOracleEligible(true);
        return candidate;
    }

    private List<ReferenceSolution> solutions() {
        return List.of(solution("java"), solution("cpp"));
    }

    private ReferenceSolution solution(String language) {
        ReferenceSolution solution = new ReferenceSolution();
        solution.setLanguage(language);
        solution.setCode(language);
        return solution;
    }

    private ValidationPrograms programs() {
        ValidationPrograms programs = new ValidationPrograms();
        programs.setValidatorJava("validator");
        programs.setOracleJava("oracle");
        return programs;
    }

    private SandboxExecuteResponse success(String output) {
        SandboxExecuteResponse response = new SandboxExecuteResponse();
        response.setStatus(1);
        response.setOutputList(List.of(output));
        return response;
    }
}

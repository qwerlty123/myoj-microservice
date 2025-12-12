package com.qwerlty.myojbackendaiservice.generation.sandbox;

import com.qwerlty.myojbackendaiservice.generation.AiCallContext;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationLane;
import com.qwerlty.myojbackendaiservice.sandbox.CodeSandboxClient;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxCaseExecutionResult;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecuteResponse;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxJudgeInfo;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAuthoringSandboxVerifierTest {

    @Test
    void classifiesCompilerFailureForTheExactArtifact() {
        CodeSandboxClient sandbox = mock(CodeSandboxClient.class);
        when(sandbox.execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> "cpp".equals(invocation.getArgument(1))
                        ? userError("Compile Error", "expected ';'")
                        : success("validator".equals(invocation.getArgument(1)) ? "VALID" : "same"));

        VerificationReport report = verifier(sandbox).verify(request(VerificationPurpose.CASE_ACCEPTANCE));

        assertThat(report.passed()).isFalse();
        assertThat(report.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(VerificationIssueCode.COMPILE_ERROR);
            assertThat(issue.target()).isEqualTo("/solutions/cpp");
            assertThat(issue.diagnostic()).contains("expected");
        });
    }

    @Test
    void classifiesRuntimeFailureFromCaseResult() {
        CodeSandboxClient sandbox = mock(CodeSandboxClient.class);
        when(sandbox.execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> "cpp".equals(invocation.getArgument(1))
                        ? caseError(9, false, false, "panic")
                        : success("validator".equals(invocation.getArgument(1)) ? "VALID" : "same"));

        VerificationReport report = verifier(sandbox).verify(request(VerificationPurpose.CASE_ACCEPTANCE));

        assertThat(report.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(VerificationIssueCode.RUNTIME_ERROR);
            assertThat(issue.caseIndex()).isZero();
            assertThat(issue.diagnostic()).contains("panic");
        });
    }

    @Test
    void classifiesTimeLimitFromCaseResult() {
        CodeSandboxClient sandbox = mock(CodeSandboxClient.class);
        when(sandbox.execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> "cpp".equals(invocation.getArgument(1))
                        ? caseError(null, true, false, "deadline exceeded")
                        : success("validator".equals(invocation.getArgument(1)) ? "VALID" : "same"));

        VerificationReport report = verifier(sandbox).verify(request(VerificationPurpose.CASE_ACCEPTANCE));

        assertThat(report.issues()).extracting(VerificationIssue::code)
                .containsExactly(VerificationIssueCode.TIME_LIMIT_EXCEEDED);
    }

    @Test
    void classifiesOutputLimitFromCaseResult() {
        CodeSandboxClient sandbox = mock(CodeSandboxClient.class);
        when(sandbox.execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> "cpp".equals(invocation.getArgument(1))
                        ? caseError(null, false, true, "output truncated")
                        : success("validator".equals(invocation.getArgument(1)) ? "VALID" : "same"));

        VerificationReport report = verifier(sandbox).verify(request(VerificationPurpose.CASE_ACCEPTANCE));

        assertThat(report.issues()).extracting(VerificationIssue::code)
                .containsExactly(VerificationIssueCode.OUTPUT_LIMIT_EXCEEDED);
    }

    @Test
    void rejectsCrossLanguageAndOracleMismatchesWithStructuredIssues() {
        CodeSandboxClient sandbox = mock(CodeSandboxClient.class);
        when(sandbox.execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong()))
                .thenReturn(success("VALID"), success("java"), success("cpp"), success("oracle"));

        VerificationReport report = verifier(sandbox).verify(request(VerificationPurpose.CASE_ACCEPTANCE));

        assertThat(report.accepted()).isEmpty();
        assertThat(report.issues()).extracting(VerificationIssue::code)
                .contains(VerificationIssueCode.CROSS_LANGUAGE_MISMATCH);
    }

    @Test
    void cachesIndividualExecutionsWithinATaskButFinalGateBypassesCache() {
        CodeSandboxClient sandbox = mock(CodeSandboxClient.class);
        when(sandbox.execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> success("validator".equals(invocation.getArgument(1))
                        ? "VALID" : "same"));
        DefaultAuthoringSandboxVerifier verifier = verifier(sandbox);

        assertThat(verifier.verify(request(VerificationPurpose.CASE_ACCEPTANCE)).passed()).isTrue();
        assertThat(verifier.verify(request(VerificationPurpose.CASE_ACCEPTANCE)).passed()).isTrue();
        verify(sandbox, times(4)).execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong());

        assertThat(verifier.verify(request(VerificationPurpose.CASE_FINAL_GATE)).passed()).isTrue();
        verify(sandbox, times(8)).execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void cacheDoesNotCrossTaskIds() {
        CodeSandboxClient sandbox = mock(CodeSandboxClient.class);
        when(sandbox.execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> success("validator".equals(invocation.getArgument(1))
                        ? "VALID" : "same"));
        DefaultAuthoringSandboxVerifier verifier = verifier(sandbox);
        try {
            AiCallContext.bind(10L, GenerationLane.PUBLIC_AUTHORING, System.currentTimeMillis() + 60_000L);
            assertThat(verifier.verify(request(VerificationPurpose.CASE_ACCEPTANCE)).passed()).isTrue();
            AiCallContext.bind(11L, GenerationLane.PUBLIC_AUTHORING, System.currentTimeMillis() + 60_000L);
            assertThat(verifier.verify(request(VerificationPurpose.CASE_ACCEPTANCE)).passed()).isTrue();
        } finally {
            AiCallContext.clear();
        }

        verify(sandbox, times(8)).execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void dependencyFailuresAreNotCached() {
        CodeSandboxClient sandbox = mock(CodeSandboxClient.class);
        when(sandbox.execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong()))
                .thenThrow(new ResourceAccessException("unavailable"))
                .thenAnswer(invocation -> success("validator".equals(invocation.getArgument(1))
                        ? "VALID" : "same"));
        DefaultAuthoringSandboxVerifier verifier = verifier(sandbox);

        assertThatThrownBy(() -> verifier.verify(request(VerificationPurpose.CASE_ACCEPTANCE)))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(verifier.verify(request(VerificationPurpose.CASE_ACCEPTANCE)).passed()).isTrue();
        verify(sandbox, times(5)).execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong());
    }

    private DefaultAuthoringSandboxVerifier verifier(CodeSandboxClient sandbox) {
        return new DefaultAuthoringSandboxVerifier(sandbox);
    }

    private VerificationRequest request(VerificationPurpose purpose) {
        CandidateTestInput candidate = new CandidateTestInput();
        candidate.setInput("1");
        candidate.setOracleEligible(true);
        ValidationPrograms programs = new ValidationPrograms();
        programs.setValidatorJava("validator");
        programs.setOracleJava("oracle");
        return new VerificationRequest(purpose, List.of(candidate),
                List.of(solution("java"), solution("cpp"), solution("go")), programs, new JudgeConfigValue());
    }

    private ReferenceSolution solution(String language) {
        ReferenceSolution solution = new ReferenceSolution();
        solution.setLanguage(language);
        solution.setCode(language);
        return solution;
    }

    private SandboxExecuteResponse success(String output) {
        SandboxExecuteResponse response = new SandboxExecuteResponse();
        response.setStatus(1);
        response.setOutputList(List.of(output));
        return response;
    }

    private SandboxExecuteResponse userError(String judgeMessage, String message) {
        SandboxExecuteResponse response = new SandboxExecuteResponse();
        response.setStatus(3);
        response.setMessage(message);
        SandboxJudgeInfo judgeInfo = new SandboxJudgeInfo();
        judgeInfo.setMessage(judgeMessage);
        response.setJudgeInfo(judgeInfo);
        return response;
    }

    private SandboxExecuteResponse caseError(Integer exitCode,
                                             boolean timedOut,
                                             boolean outputLimitExceeded,
                                             String error) {
        SandboxExecuteResponse response = new SandboxExecuteResponse();
        response.setStatus(3);
        SandboxCaseExecutionResult caseResult = new SandboxCaseExecutionResult();
        caseResult.setIndex(0);
        caseResult.setExitCode(exitCode);
        caseResult.setTimedOut(timedOut);
        caseResult.setOutputLimitExceeded(outputLimitExceeded);
        caseResult.setError(error);
        response.setCaseResults(List.of(caseResult));
        return response;
    }
}

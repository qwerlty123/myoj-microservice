package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.generation.AuthoringAgentModel;
import com.qwerlty.myojbackendaiservice.generation.ProblemGenerationModel;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CoveragePlan;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CoverageRisk;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemSourceDraft;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.TestCaseTaskRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import com.qwerlty.myojbackendaiservice.sandbox.CodeSandboxClient;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecuteResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestCaseGenerationWorkflowTest {

    @Test
    void agentCanUseValidationFeedbackAcrossRoundsUntilTheHardGatePasses() {
        ProblemGenerationModel structured = mock(ProblemGenerationModel.class);
        when(structured.generateCoveragePlan(any(), anyString())).thenReturn(coveragePlan());
        when(structured.generateReferenceSolution(any(), anyString()))
                .thenAnswer(invocation -> solution(invocation.getArgument(1)));
        ValidationPrograms programs = new ValidationPrograms();
        programs.setValidatorJava("validator");
        programs.setOracleJava("oracle");
        when(structured.generateValidationPrograms(any())).thenReturn(programs);

        CodeSandboxClient sandbox = mock(CodeSandboxClient.class);
        when(sandbox.execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    String code = invocation.getArgument(1);
                    List<String> inputs = invocation.getArgument(2);
                    if ("validator".equals(code)) {
                        return successful(inputs.stream().map(input -> input.equals("bad") ? "INVALID" : "VALID").toList());
                    }
                    return successful(inputs.stream().map(input -> "out:" + input).toList());
                });

        AuthoringAgentModel agent = new AuthoringAgentModel() {
            @Override
            public void generateTestCases(TestCaseAgentPrompt prompt, TestCaseAgentTools tools) {
                tools.evaluateCandidateCases(List.of(candidate("bad", "NORMAL", "overflow"),
                        candidate("0", "BOUNDARY", "overflow")));
                List<CandidateTestInput> second = new ArrayList<>();
                String[] categories = {"NORMAL", "BOUNDARY", "MAXIMUM", "ADVERSARIAL"};
                for (int index = 1; index <= 9; index++) {
                    second.add(candidate(String.valueOf(index), categories[index % categories.length],
                            index == 1 ? "overflow" : null));
                }
                tools.evaluateCandidateCases(second);
            }

            @Override
            public com.qwerlty.myojbackendaiservice.model.dto.generation.QualityModelReview reviewQuality(
                    QualityAgentPrompt prompt, QualityEvidenceTools tools) {
                throw new UnsupportedOperationException();
            }
        };
        TestCaseGenerationWorkflow workflow = new TestCaseGenerationWorkflow(
                structured, agent, new SandboxBatchVerifier(sandbox), new ObjectMapper());

        var artifact = workflow.execute(WorkflowContext.testing(2L), request());

        assertThat(artifact.getJudgeCases()).hasSize(10);
        assertThat(artifact.getCoverage().getRejectedCount()).isEqualTo(1);
        assertThat(artifact.getCoverage().getUncoveredRiskIds()).isEmpty();
        assertThat(artifact.getToolTrace()).hasSize(2);
        assertThat(artifact.getJudgeCases()).extracting("output").contains("out:0", "out:9");
    }

    private TestCaseTaskRequest request() {
        ProblemSourceDraft draft = new ProblemSourceDraft();
        draft.setTitle("Sum");
        draft.setContent("## 题目描述\n求和。\n## 输入格式\n整数。\n## 输出格式\n整数。\n## 数据范围\n0 <= n <= 9");
        draft.setDifficulty(1);
        draft.setTags(List.of("math"));
        draft.setJudgeConfig(new JudgeConfigValue());
        TestCaseTaskRequest request = new TestCaseTaskRequest();
        request.setSourceDraft(draft);
        request.setCaseCount(10);
        request.setConstraints("覆盖溢出");
        return request;
    }

    private CoveragePlan coveragePlan() {
        CoverageRisk risk = new CoverageRisk();
        risk.setId("overflow");
        risk.setDescription("整数溢出");
        CoveragePlan plan = new CoveragePlan();
        plan.setDynamicRisks(List.of(risk));
        return plan;
    }

    private CandidateTestInput candidate(String input, String category, String risk) {
        CandidateTestInput value = new CandidateTestInput();
        value.setInput(input);
        value.setCategory(category);
        value.setOracleEligible(true);
        value.setRiskIds(risk == null ? List.of() : List.of(risk));
        return value;
    }

    private ReferenceSolution solution(String language) {
        ReferenceSolution solution = new ReferenceSolution();
        solution.setLanguage(language);
        solution.setCode(language + "-solution");
        return solution;
    }

    private SandboxExecuteResponse successful(List<String> outputs) {
        SandboxExecuteResponse response = new SandboxExecuteResponse();
        response.setStatus(1);
        response.setOutputList(outputs);
        return response;
    }
}

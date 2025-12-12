package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.generation.AuthoringAgentModel;
import com.qwerlty.myojbackendaiservice.generation.ProblemGenerationModel;
import com.qwerlty.myojbackendaiservice.generation.knowledge.AuthoringKnowledgeRetriever;
import com.qwerlty.myojbackendaiservice.generation.sandbox.DefaultAuthoringSandboxVerifier;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CaseEvidence;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CoveragePlan;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CoverageRisk;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemSourceDraft;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.TestCaseTaskRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStage;
import com.qwerlty.myojbackendaiservice.sandbox.CodeSandboxClient;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecuteResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        var artifact = workflow(agent).execute(WorkflowContext.testing(2L, AuthoringTaskType.TEST_CASES), request());

        assertThat(artifact.getJudgeCases()).hasSize(10);
        assertThat(artifact.getCoverage().getRejectedCount()).isEqualTo(1);
        assertThat(artifact.getCoverage().getUncoveredRiskIds()).isEmpty();
        assertThat(artifact.getToolTrace()).hasSize(2);
        assertThat(artifact.getJudgeCases()).extracting("output").contains("out:0", "out:9");
    }

    @Test
    void toolReservesCapacityForRequiredCategoriesBeforeCountCanReachTarget() {
        AuthoringAgentModel agent = new AuthoringAgentModel() {
            @Override
            public void generateTestCases(TestCaseAgentPrompt prompt, TestCaseAgentTools tools) {
                List<CandidateTestInput> normalCases = new ArrayList<>();
                for (int index = 0; index < 8; index++) {
                    normalCases.add(candidate("normal-" + index, "NORMAL", null));
                }
                var first = tools.evaluateCandidateCases(normalCases);
                assertThat(first.getAccepted()).isEqualTo(7);
                assertThat(first.getRejected()).isEqualTo(1);
                assertThat(first.getMissingCategories())
                        .containsExactly("BOUNDARY", "MAXIMUM", "ADVERSARIAL");
                var second = tools.evaluateCandidateCases(List.of(
                        candidate("boundary", "BOUNDARY", null),
                        candidate("maximum", "MAXIMUM", null),
                        candidate("adversarial", "ADVERSARIAL", null)));
                assertThat(second.getTotalAccepted()).isEqualTo(10);
                assertThat(second.getMissingCategories()).isEmpty();
            }

            @Override
            public com.qwerlty.myojbackendaiservice.model.dto.generation.QualityModelReview reviewQuality(
                    QualityAgentPrompt prompt, QualityEvidenceTools tools) {
                throw new UnsupportedOperationException();
            }
        };

        var artifact = workflow(agent).execute(WorkflowContext.testing(3L, AuthoringTaskType.TEST_CASES), request());

        assertThat(artifact.getJudgeCases()).hasSize(10);
        assertThat(artifact.getJudgeCases()).extracting("category")
                .contains("NORMAL", "BOUNDARY", "MAXIMUM", "ADVERSARIAL");
        assertThat(artifact.getToolTrace()).extracting(ToolCallTrace::outcome)
                .containsExactly("CONTINUE", "TARGET_REACHED");
    }

    @Test
    void resumedCheckpointWithTenNormalCasesIsRepairedBeforeAgentContinues() {
        TestCaseGenerationState resumed = new TestCaseGenerationState();
        GeneratedProblemSpec specification = new GeneratedProblemSpec();
        specification.setJudgeConfig(new JudgeConfigValue());
        resumed.setSpecification(specification);
        resumed.setCoveragePlan(coveragePlan());
        resumed.setSolutions(List.of(solution("java"), solution("cpp")));
        ValidationPrograms programs = new ValidationPrograms();
        programs.setValidatorJava("validator");
        programs.setOracleJava("oracle");
        resumed.setPrograms(programs);
        resumed.setRounds(7);
        for (int index = 0; index < 10; index++) {
            CandidateTestInput candidate = candidate("normal-" + index, "NORMAL", null);
            resumed.getAcceptedCases().add(new AcceptedCaseState(candidate,
                    "out:" + candidate.getInput(), new CaseEvidence()));
        }

        ObjectMapper objectMapper = new ObjectMapper();
        WorkflowCheckpoint checkpoint = new WorkflowCheckpoint(1, "test-v1",
                GenerationStage.FINAL_VALIDATION.name(), objectMapper.valueToTree(resumed), List.of());
        WorkflowCheckpointStore store = new WorkflowCheckpointStore() {
            @Override public Optional<WorkflowCheckpoint> load() { return Optional.of(checkpoint); }
            @Override public void save(WorkflowCheckpoint ignored) { }
            @Override public void clear() { }
        };
        WorkflowContext context = new WorkflowContext(4L, AuthoringTaskType.TEST_CASES,
                "test-v1", 60_000L, objectMapper, stage -> { }, store, () -> false,
                new SimpleMeterRegistry());
        AuthoringAgentModel agent = new AuthoringAgentModel() {
            @Override
            public void generateTestCases(TestCaseAgentPrompt prompt, TestCaseAgentTools tools) {
                assertThat(tools.categoryCounts()).containsEntry("NORMAL", 7);
                var result = tools.evaluateCandidateCases(List.of(
                        candidate("boundary", "BOUNDARY", null),
                        candidate("maximum", "MAXIMUM", null),
                        candidate("adversarial", "ADVERSARIAL", null)));
                assertThat(result.getRound()).isEqualTo(1);
            }

            @Override
            public com.qwerlty.myojbackendaiservice.model.dto.generation.QualityModelReview reviewQuality(
                    QualityAgentPrompt prompt, QualityEvidenceTools tools) {
                throw new UnsupportedOperationException();
            }
        };

        var artifact = workflow(agent).execute(context, request());

        assertThat(artifact.getJudgeCases()).hasSize(10);
        assertThat(artifact.getJudgeCases()).extracting("category")
                .contains("NORMAL", "BOUNDARY", "MAXIMUM", "ADVERSARIAL");
    }

    @Test
    void workflowRestartsAgentWhenModelStopsBeforeTargetIsReached() {
        int[] invocations = {0};
        AuthoringAgentModel agent = new AuthoringAgentModel() {
            @Override
            public void generateTestCases(TestCaseAgentPrompt prompt, TestCaseAgentTools tools) {
                invocations[0]++;
                if (invocations[0] == 1) {
                    String[] categories = {
                            "NORMAL", "BOUNDARY", "MAXIMUM", "ADVERSARIAL",
                            "NORMAL", "NORMAL", "NORMAL", "NORMAL", "NORMAL"
                    };
                    List<CandidateTestInput> firstNine = new ArrayList<>();
                    for (int index = 0; index < categories.length; index++) {
                        firstNine.add(candidate("early-stop-" + index, categories[index], null));
                    }
                    var result = tools.evaluateCandidateCases(firstNine);
                    assertThat(result.getTotalAccepted()).isEqualTo(9);
                    return;
                }
                tools.evaluateCandidateCases(List.of(candidate("final-case", "NORMAL", null)));
            }

            @Override
            public com.qwerlty.myojbackendaiservice.model.dto.generation.QualityModelReview reviewQuality(
                    QualityAgentPrompt prompt, QualityEvidenceTools tools) {
                throw new UnsupportedOperationException();
            }
        };

        var artifact = workflow(agent).execute(WorkflowContext.testing(5L, AuthoringTaskType.TEST_CASES), request());

        assertThat(invocations[0]).isEqualTo(2);
        assertThat(artifact.getJudgeCases()).hasSize(10);
    }

    private TestCaseGenerationWorkflow workflow(AuthoringAgentModel agent) {
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
                        return successful(inputs.stream()
                                .map(input -> input.equals("bad") ? "INVALID" : "VALID").toList());
                    }
                    return successful(inputs.stream().map(input -> "out:" + input).toList());
                });
        return new TestCaseGenerationWorkflow(
                structured, agent, new DefaultAuthoringSandboxVerifier(sandbox), new ObjectMapper(),
                mock(AuthoringKnowledgeRetriever.class));
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

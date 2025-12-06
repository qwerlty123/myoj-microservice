package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.generation.AuthoringAgentModel;
import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.generation.ProblemGenerationModel;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CoveragePlan;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CoverageReport;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedJudgeCase;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GenerationValidationReport;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemSourceDraft;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.TestCaseArtifact;
import com.qwerlty.myojbackendaiservice.model.dto.generation.TestCaseTaskRequest;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Component
public class TestCaseGenerationWorkflow implements AuthoringWorkflow<TestCaseTaskRequest, TestCaseArtifact> {
    private final ProblemGenerationModel structuredModel;
    private final AuthoringAgentModel agentModel;
    private final SandboxBatchVerifier verifier;
    private final ObjectMapper objectMapper;

    public TestCaseGenerationWorkflow(ProblemGenerationModel structuredModel,
                                      AuthoringAgentModel agentModel,
                                      SandboxBatchVerifier verifier,
                                      ObjectMapper objectMapper) {
        this.structuredModel = structuredModel;
        this.agentModel = agentModel;
        this.verifier = verifier;
        this.objectMapper = objectMapper;
    }

    @Override public AuthoringTaskType type() { return AuthoringTaskType.TEST_CASES; }
    @Override public Class<TestCaseTaskRequest> requestType() { return TestCaseTaskRequest.class; }

    @Override
    public TestCaseArtifact execute(WorkflowContext context, TestCaseTaskRequest request) {
        validateSource(request.getSourceDraft());
        int target = request.getCaseCount();
        TestCaseGenerationState state = context.resume(TestCaseGenerationState.class)
                .orElseGet(TestCaseGenerationState::new);
        if (state.getSpecification() == null) {
            context.stage(GenerationStage.ANALYZING_SOURCE);
            state.setSpecification(fromSource(request.getSourceDraft()));
            context.checkpoint(GenerationStage.ANALYZING_SOURCE, state);
        }
        if (state.getCoveragePlan() == null) {
            context.stage(GenerationStage.PLANNING_COVERAGE);
            CoveragePlan plan = structuredModel.generateCoveragePlan(state.getSpecification(), request.getConstraints());
            state.setCoveragePlan(plan == null ? new CoveragePlan() : plan);
            state.setSolutions(List.of(solution(state.getSpecification(), "java"),
                    solution(state.getSpecification(), "cpp")));
            state.setPrograms(structuredModel.generateValidationPrograms(state.getSpecification()));
            context.checkpoint(GenerationStage.PLANNING_COVERAGE, state);
        }
        context.stage(GenerationStage.AGENT_GENERATING_CASES);
        state.setRounds(0);
        TestCaseAgentTools tools = new TestCaseAgentTools(context, verifier, state, target);
        tools.reopenSlotsForMissingCategories();
        while (generationIncomplete(state, tools, target) && tools.hasRemainingRounds()) {
            int roundsBefore = state.getRounds();
            agentModel.generateTestCases(new TestCaseAgentPrompt(state.getSpecification(),
                    state.getCoveragePlan(), target, request.getConstraints()), tools);
            if (state.getRounds() == roundsBefore) {
                throw new GenerationValidationException("测试用例 Agent 未调用验收工具");
            }
        }
        context.stage(GenerationStage.FINAL_VALIDATION);
        hardGate(state, tools, target);
        BatchVerificationResult finalVerification = verifier.verify(
                state.getAcceptedCases().stream().map(AcceptedCaseState::getCandidate).toList(),
                state.getSolutions(), state.getPrograms(), config(state));
        if (!finalVerification.rejected().isEmpty() || finalVerification.accepted().size() != target) {
            throw new GenerationValidationException("最终用例集合未通过独立复核");
        }

        List<GeneratedJudgeCase> judgeCases = finalVerification.accepted().stream()
                .map(item -> new GeneratedJudgeCase(item.candidate().getInput(), item.output(),
                        item.candidate().getCategory())).toList();
        CoverageReport coverage = coverage(state, tools, target);
        GenerationValidationReport validation = validation(finalVerification, tools);
        return new TestCaseArtifact(DraftFingerprint.source(request.getSourceDraft(), objectMapper),
                judgeCases, coverage, validation, List.copyOf(context.toolTrace()), false);
    }

    private void validateSource(ProblemSourceDraft source) {
        if (source == null || blank(source.getTitle()) || blank(source.getContent())) {
            throw new GenerationValidationException("生成测试用例需要完整的题目标题和题面");
        }
    }

    private GeneratedProblemSpec fromSource(ProblemSourceDraft source) {
        GeneratedProblemSpec spec = new GeneratedProblemSpec();
        spec.setTitle(source.getTitle());
        spec.setContent(source.getContent());
        spec.setDifficulty(source.getDifficulty() == null ? 1 : source.getDifficulty());
        spec.setTags(source.getTags() == null ? List.of() : source.getTags());
        spec.setSolutionExplanation(blank(source.getAnswer()) ? "请根据题面独立推导正确算法。" : source.getAnswer());
        spec.setJudgeConfig(source.getJudgeConfig() == null ? new JudgeConfigValue() : source.getJudgeConfig());
        return spec;
    }

    private ReferenceSolution solution(GeneratedProblemSpec spec, String language) {
        ReferenceSolution solution = structuredModel.generateReferenceSolution(spec, language);
        if (solution == null || blank(solution.getCode())) {
            throw new GenerationValidationException(language + " 独立校验解为空");
        }
        solution.setLanguage(language);
        return solution;
    }

    private void hardGate(TestCaseGenerationState state, TestCaseAgentTools tools, int target) {
        if (state.getAcceptedCases().size() != target) {
            throw new GenerationValidationException("有效测试用例数量不足，要求 " + target
                    + "，实际 " + state.getAcceptedCases().size());
        }
        List<String> missing = tools.missingRequiredCategories();
        if (!missing.isEmpty()) {
            throw new GenerationValidationException("缺少关键测试类别: " + String.join(", ", missing));
        }
    }

    private boolean generationIncomplete(TestCaseGenerationState state, TestCaseAgentTools tools, int target) {
        return state.getAcceptedCases().size() < target || !tools.missingRequiredCategories().isEmpty();
    }

    private CoverageReport coverage(TestCaseGenerationState state, TestCaseAgentTools tools, int target) {
        CoverageReport report = new CoverageReport();
        report.setTargetCount(target);
        report.setAcceptedCount(state.getAcceptedCases().size());
        report.setRejectedCount(state.getRejectedCount());
        report.setCategoryCounts(tools.categoryCounts());
        report.setDynamicRisks(state.getCoveragePlan().getDynamicRisks() == null
                ? List.of() : state.getCoveragePlan().getDynamicRisks());
        report.setUncoveredRiskIds(tools.uncoveredRiskIds());
        if (!report.getUncoveredRiskIds().isEmpty()) {
            report.getWarnings().add("以下题目特有风险尚未覆盖: " + String.join(", ", report.getUncoveredRiskIds()));
        }
        return report;
    }

    private GenerationValidationReport validation(BatchVerificationResult verified, TestCaseAgentTools tools) {
        GenerationValidationReport report = new GenerationValidationReport();
        report.setCompiledLanguages(new ArrayList<>(List.of("java", "cpp")));
        report.setCrossLanguageMatched(true);
        report.setValidatorPassed(true);
        report.setOracleMatched(true);
        report.setTotalCases(verified.accepted().size());
        report.setOracleCases(verified.oracleCases());
        report.setDuplicateCases(0);
        report.setCategoryCounts(new LinkedHashMap<>(tools.categoryCounts()));
        report.setQualityScore(tools.uncoveredRiskIds().isEmpty() ? 100 : 92);
        if (!tools.uncoveredRiskIds().isEmpty()) report.getWarnings().add("存在非关键动态覆盖缺口");
        return report;
    }

    private JudgeConfigValue config(TestCaseGenerationState state) {
        return state.getSpecification().getJudgeConfig() == null
                ? new JudgeConfigValue() : state.getSpecification().getJudgeConfig();
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}

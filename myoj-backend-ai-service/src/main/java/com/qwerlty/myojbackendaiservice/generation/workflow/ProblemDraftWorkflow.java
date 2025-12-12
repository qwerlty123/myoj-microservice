package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.generation.AuthoringAgentModel;
import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.generation.ProblemGenerationModel;
import com.qwerlty.myojbackendaiservice.generation.sandbox.AuthoringSandboxVerifier;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationPurpose;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationReport;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedJudgeCase;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemDraft;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GenerationValidationReport;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftArtifact;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftTaskRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStage;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Component
public class ProblemDraftWorkflow implements AuthoringWorkflow<ProblemDraftTaskRequest, ProblemDraftArtifact> {
    private static final List<String> LANGUAGES = List.of("java", "cpp", "go");
    private static final int MAX_SPEC_GENERATION_ATTEMPTS = 2;

    private final ProblemGenerationModel model;
    private final AuthoringAgentModel agentModel;
    private final AuthoringSandboxVerifier verifier;
    private final ObjectMapper objectMapper;

    public ProblemDraftWorkflow(ProblemGenerationModel model,
                                AuthoringAgentModel agentModel,
                                AuthoringSandboxVerifier verifier,
                                ObjectMapper objectMapper) {
        this.model = model;
        this.agentModel = agentModel;
        this.verifier = verifier;
        this.objectMapper = objectMapper;
    }

    @Override public AuthoringTaskType type() { return AuthoringTaskType.PROBLEM_DRAFT; }
    @Override public Class<ProblemDraftTaskRequest> requestType() { return ProblemDraftTaskRequest.class; }

    @Override
    public ProblemDraftArtifact execute(WorkflowContext context, ProblemDraftTaskRequest request) {
        DraftState state = context.resume(DraftState.class).orElseGet(DraftState::new);
        if (state.getSpecification() == null) {
            context.stage(GenerationStage.DRAFTING_SPEC);
            state.setSpecification(generateSpecification(request));
            context.checkpoint(GenerationStage.DRAFTING_SPEC, state);
        }
        ensureDownstream(context, state);
        context.stage(GenerationStage.VERIFYING_SAMPLES);
        VerificationReport verification = verify(state, VerificationPurpose.DRAFT_REPAIR);
        updateVerificationState(context, state, verification);
        ProblemDraftTools tools = new ProblemDraftTools(context, verifier, state, objectMapper);
        while (!verification.passed() && tools.hasRemainingCalls()) {
            int callsBefore = state.getRepairCalls();
            agentModel.repairProblemDraft(new DraftRepairPrompt(
                    state.getSpecification(), List.copyOf(state.getSolutions()), state.getPrograms(),
                    verification, tools.currentStateHash(), tools.remainingCalls()), tools);
            if (state.getRepairCalls() == callsBefore) {
                throw new GenerationValidationException("题目草稿 Agent 未调用修复工具");
            }
            ensureDownstream(context, state);
            context.stage(GenerationStage.VERIFYING_SAMPLES);
            verification = verify(state, VerificationPurpose.DRAFT_REPAIR);
            updateVerificationState(context, state, verification);
        }
        if (!verification.passed()) {
            throw new GenerationValidationException("题目草稿局部修复耗尽: " + verification.summary());
        }

        VerificationReport finalVerification = verify(state, VerificationPurpose.DRAFT_FINAL_GATE);
        updateVerificationState(context, state, finalVerification);
        if (!finalVerification.passed()
                || finalVerification.accepted().size() != state.getSpecification().getSampleInputs().size()) {
            throw new GenerationValidationException("题目草稿未通过最终独立门禁: " + finalVerification.summary());
        }
        GeneratedProblemDraft draft = buildDraft(state, finalVerification);
        validatePersistenceSize(draft);
        GenerationValidationReport report = report(state, finalVerification);
        return new ProblemDraftArtifact(draft, report, List.copyOf(context.toolTrace()), false);
    }

    private void ensureDownstream(WorkflowContext context, DraftState state) {
        if (!state.getSolutions().isEmpty() && state.getPrograms() != null) return;
        context.stage(GenerationStage.GENERATING_REFERENCE_SOLUTIONS);
        state.setSolutions(generateSolutions(state.getSpecification()));
        state.setPrograms(model.generateValidationPrograms(state.getSpecification()));
        state.setStateHash(ProblemDraftTools.stateHash(state, objectMapper));
        context.checkpoint(GenerationStage.GENERATING_REFERENCE_SOLUTIONS, state);
    }

    private VerificationReport verify(DraftState state, VerificationPurpose purpose) {
        return verifier.verify(new VerificationRequest(purpose, candidates(state.getSpecification()),
                state.getSolutions(), state.getPrograms(), normalizedConfig(state.getSpecification())));
    }

    private void updateVerificationState(WorkflowContext context,
                                         DraftState state,
                                         VerificationReport verification) {
        state.setLastVerificationSummary(verification.summary());
        state.setStateHash(ProblemDraftTools.stateHash(state, objectMapper));
        context.checkpoint(GenerationStage.VERIFYING_SAMPLES, state);
    }

    private GeneratedProblemSpec generateSpecification(ProblemDraftTaskRequest request) {
        GenerationValidationException failure = null;
        for (int attempt = 0; attempt <= MAX_SPEC_GENERATION_ATTEMPTS; attempt++) {
            try {
                GeneratedProblemSpec spec = model.generateDraftSpecification(request.getRequirements());
                validateSpecification(spec);
                return spec;
            } catch (GenerationValidationException exception) {
                failure = exception;
            }
        }
        throw failure == null ? new GenerationValidationException("模型未生成题目规格") : failure;
    }

    private List<ReferenceSolution> generateSolutions(GeneratedProblemSpec spec) {
        List<ReferenceSolution> solutions = new ArrayList<>();
        for (String language : LANGUAGES) {
            ReferenceSolution solution = model.generateReferenceSolution(spec, language);
            if (solution == null || solution.getCode() == null || solution.getCode().isBlank()) {
                throw new GenerationValidationException(language + " 参考实现为空");
            }
            solution.setLanguage(language);
            solutions.add(solution);
        }
        return solutions;
    }

    private void validateSpecification(GeneratedProblemSpec spec) {
        if (spec == null || blank(spec.getTitle()) || blank(spec.getContent()) || blank(spec.getSolutionExplanation())) {
            throw new GenerationValidationException("题目规格缺少必要字段");
        }
        if (spec.getSampleInputs() == null || spec.getSampleInputs().size() < 2 || spec.getSampleInputs().size() > 3
                || spec.getSampleInputs().stream().anyMatch(input -> input == null || blank(input.getInput()))) {
            throw new GenerationValidationException("题目草稿必须包含 2 到 3 个基础样例输入");
        }
    }

    static List<CandidateTestInput> candidates(GeneratedProblemSpec spec) {
        return spec.getSampleInputs().stream().map(input -> {
            CandidateTestInput candidate = new CandidateTestInput();
            candidate.setInput(input.getInput().replace("\r\n", "\n"));
            candidate.setCategory(input.getCategory());
            candidate.setOracleEligible(true);
            return candidate;
        }).toList();
    }

    private GeneratedProblemDraft buildDraft(DraftState state, VerificationReport verified) {
        GeneratedProblemSpec spec = state.getSpecification();
        GeneratedProblemDraft draft = new GeneratedProblemDraft();
        draft.setTitle(spec.getTitle());
        draft.setDifficulty(spec.getDifficulty());
        draft.setTags(spec.getTags() == null ? List.of() : spec.getTags());
        draft.setJudgeConfig(normalizedConfig(spec));
        draft.setReferenceSolutions(state.getSolutions());
        List<GeneratedJudgeCase> samples = verified.accepted().stream()
                .map(item -> new GeneratedJudgeCase(item.candidate().getInput(), item.output(),
                        normalizeCategory(item.candidate().getCategory())))
                .toList();
        draft.setJudgeCase(samples);
        draft.setContent(renderContent(spec.getContent(), samples));
        ReferenceSolution java = state.getSolutions().stream()
                .filter(solution -> "java".equals(solution.getLanguage())).findFirst().orElseThrow();
        draft.setAnswer(spec.getSolutionExplanation() + "\n\n## Java 参考实现\n\n```java\n"
                + java.getCode() + "\n```\n");
        return draft;
    }

    private String renderContent(String content, List<GeneratedJudgeCase> samples) {
        StringBuilder result = new StringBuilder(content.stripTrailing());
        for (int index = 0; index < samples.size(); index++) {
            GeneratedJudgeCase sample = samples.get(index);
            result.append("\n\n## 示例 ").append(index + 1)
                    .append("\n\n### 输入\n\n```text\n").append(sample.getInput())
                    .append("\n```\n\n### 输出\n\n```text\n").append(sample.getOutput()).append("\n```");
        }
        return result.toString();
    }

    private GenerationValidationReport report(DraftState state, VerificationReport verified) {
        GenerationValidationReport report = new GenerationValidationReport();
        report.setCompiledLanguages(new ArrayList<>(LANGUAGES));
        report.setCrossLanguageMatched(true);
        report.setValidatorPassed(true);
        report.setOracleMatched(true);
        report.setTotalCases(verified.accepted().size());
        report.setOracleCases(verified.oracleCases());
        report.setDuplicateCases(0);
        report.setQualityScore(100);
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        verified.accepted().forEach(item -> counts.merge(
                normalizeCategory(item.candidate().getCategory()), 1, Integer::sum));
        report.setCategoryCounts(counts);
        return report;
    }

    static com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue normalizedConfig(
            GeneratedProblemSpec spec) {
        return spec.getJudgeConfig() == null
                ? new com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue()
                : spec.getJudgeConfig();
    }

    private void validatePersistenceSize(GeneratedProblemDraft draft) {
        try {
            if (objectMapper.writeValueAsBytes(draft).length > 4 * 1024 * 1024) {
                throw new GenerationValidationException("题目草稿超过持久化大小限制");
            }
        } catch (JsonProcessingException exception) {
            throw new GenerationValidationException("题目草稿无法序列化");
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String normalizeCategory(String value) { return blank(value) ? "NORMAL" : value.trim().toUpperCase(); }

    @Data
    public static class DraftState {
        private GeneratedProblemSpec specification;
        private List<ReferenceSolution> solutions = new ArrayList<>();
        private ValidationPrograms programs;
        private int repairCalls;
        private int specificationRepairCalls;
        private String lastVerificationSummary;
        private String stateHash;
    }
}

package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.generation.ProblemGenerationModel;
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
    private static final int MAX_REPAIR_ATTEMPTS = 2;

    private final ProblemGenerationModel model;
    private final SandboxBatchVerifier verifier;
    private final ObjectMapper objectMapper;

    public ProblemDraftWorkflow(ProblemGenerationModel model,
                                SandboxBatchVerifier verifier,
                                ObjectMapper objectMapper) {
        this.model = model;
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
        BatchVerificationResult verification = null;
        for (int repair = 0; repair <= MAX_REPAIR_ATTEMPTS; repair++) {
            try {
                if (state.getSolutions().isEmpty() || state.getPrograms() == null) {
                    context.stage(GenerationStage.GENERATING_REFERENCE_SOLUTIONS);
                    state.setSolutions(generateSolutions(state.getSpecification()));
                    state.setPrograms(model.generateValidationPrograms(state.getSpecification()));
                    context.checkpoint(GenerationStage.GENERATING_REFERENCE_SOLUTIONS, state);
                }
                context.stage(GenerationStage.VERIFYING_SAMPLES);
                verification = verifier.verify(candidates(state.getSpecification()), state.getSolutions(),
                        state.getPrograms(), normalizedConfig(state.getSpecification()));
                if (!verification.rejected().isEmpty()
                        || verification.accepted().size() != state.getSpecification().getSampleInputs().size()) {
                    throw new GenerationValidationException("基础样例未通过多语言交叉验证");
                }
                break;
            } catch (GenerationValidationException exception) {
                if (repair >= MAX_REPAIR_ATTEMPTS) throw exception;
                state.setSolutions(new ArrayList<>());
                state.setPrograms(null);
            }
        }
        GeneratedProblemDraft draft = buildDraft(state, verification);
        validatePersistenceSize(draft);
        GenerationValidationReport report = report(state, verification);
        return new ProblemDraftArtifact(draft, report, List.copyOf(context.toolTrace()), false);
    }

    private GeneratedProblemSpec generateSpecification(ProblemDraftTaskRequest request) {
        GenerationValidationException failure = null;
        for (int attempt = 0; attempt <= MAX_REPAIR_ATTEMPTS; attempt++) {
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

    private List<CandidateTestInput> candidates(GeneratedProblemSpec spec) {
        return spec.getSampleInputs().stream().map(input -> {
            CandidateTestInput candidate = new CandidateTestInput();
            candidate.setInput(input.getInput().replace("\r\n", "\n"));
            candidate.setCategory(input.getCategory());
            candidate.setOracleEligible(true);
            return candidate;
        }).toList();
    }

    private GeneratedProblemDraft buildDraft(DraftState state, BatchVerificationResult verified) {
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

    private GenerationValidationReport report(DraftState state, BatchVerificationResult verified) {
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

    private com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue normalizedConfig(
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
    }
}

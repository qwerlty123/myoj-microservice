package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.generation.AuthoringAgentModel;
import com.qwerlty.myojbackendaiservice.generation.ProblemGenerationModel;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CaseEvidence;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedJudgeCase;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemSourceDraft;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityIssue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityModelReview;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityPatch;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityPatchSuggestion;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityReport;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityReviewArtifact;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityReviewTaskRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityVerificationSummary;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class QuestionQualityWorkflow implements AuthoringWorkflow<QualityReviewTaskRequest, QualityReviewArtifact> {
    private static final Set<String> DIMENSIONS =
            Set.of("COMPLETENESS", "CONSISTENCY", "SOLUTION", "TEST_CASES", "JUDGE_CONFIG");
    private static final Set<String> SEVERITIES = Set.of("BLOCKER", "MAJOR", "MINOR", "INFO");
    private static final Set<String> PATCH_TARGETS = Set.of(
            "/title", "/content", "/difficulty", "/tags",
            "/judgeConfig/timeLimit", "/judgeConfig/memoryLimit", "/judgeConfig/stackLimit");

    private final ProblemGenerationModel structuredModel;
    private final AuthoringAgentModel agentModel;
    private final SandboxBatchVerifier verifier;
    private final ObjectMapper objectMapper;

    public QuestionQualityWorkflow(ProblemGenerationModel structuredModel,
                                   AuthoringAgentModel agentModel,
                                   SandboxBatchVerifier verifier,
                                   ObjectMapper objectMapper) {
        this.structuredModel = structuredModel;
        this.agentModel = agentModel;
        this.verifier = verifier;
        this.objectMapper = objectMapper;
    }

    @Override public AuthoringTaskType type() { return AuthoringTaskType.QUALITY_REVIEW; }
    @Override public Class<QualityReviewTaskRequest> requestType() { return QualityReviewTaskRequest.class; }

    @Override
    public QualityReviewArtifact execute(WorkflowContext context, QualityReviewTaskRequest request) {
        ProblemSourceDraft source = request.getSourceDraft();
        context.stage(GenerationStage.STATIC_CHECKING);
        List<QualityIssue> issues = deterministicIssues(source);
        List<QualityPatch> patches = duplicatePatches(source, issues);
        String sourceHash = DraftFingerprint.source(source, objectMapper);
        if (!isComplete(source)) {
            QualityReport report = report(false, issues, new QualityVerificationSummary());
            report.getVerification().getSkippedChecks().addAll(
                    List.of("语义审查", "Java/C++ 独立解", "小数据 Oracle", "已有用例交叉验证"));
            return new QualityReviewArtifact(sourceHash, report, patches,
                    List.copyOf(context.toolTrace()), false);
        }

        QualityReviewState state = context.resume(QualityReviewState.class).orElseGet(QualityReviewState::new);
        if (state.getSpecification() == null) {
            context.stage(GenerationStage.SEMANTIC_REVIEWING);
            state.setSpecification(fromSource(source));
            state.setSolutions(List.of(solution(state.getSpecification(), "java"),
                    solution(state.getSpecification(), "cpp")));
            state.setPrograms(structuredModel.generateValidationPrograms(state.getSpecification()));
            context.checkpoint(GenerationStage.SEMANTIC_REVIEWING, state);
        }
        context.stage(GenerationStage.VERIFYING_EXISTING_CASES);
        BatchVerificationResult baseline = verifier.verify(candidates(source), state.getSolutions(),
                state.getPrograms(), config(source));
        state.setBaselineEvidence(evidence(source, baseline));
        context.checkpoint(GenerationStage.VERIFYING_EXISTING_CASES, state);
        addExecutionIssuesAndPatches(source, baseline, issues, patches);

        context.stage(GenerationStage.AGENT_EVIDENCE_REVIEW);
        QualityEvidenceTools tools = new QualityEvidenceTools(context,
                indexes -> inspectEvidence(indexes, state.getBaselineEvidence()));
        QualityModelReview modelReview = agentModel.reviewQuality(
                new QualityAgentPrompt(source, List.copyOf(issues), List.copyOf(state.getBaselineEvidence())), tools);
        mergeModelReview(source, state, modelReview, issues, patches);

        context.stage(GenerationStage.BUILDING_REPORT);
        QualityVerificationSummary summary = verificationSummary(source, baseline);
        QualityReport report = report(true, issues, summary);
        return new QualityReviewArtifact(sourceHash, report, patches,
                List.copyOf(context.toolTrace()), false);
    }

    private List<QualityIssue> deterministicIssues(ProblemSourceDraft source) {
        List<QualityIssue> issues = new ArrayList<>();
        if (source == null) {
            issues.add(issue("missing-draft", "COMPLETENESS", "BLOCKER", "题目草稿为空", "无法执行质检"));
            return issues;
        }
        if (blank(source.getTitle())) issues.add(issue("missing-title", "COMPLETENESS", "MAJOR", "标题为空", "请填写题目标题"));
        if (blank(source.getContent())) issues.add(issue("missing-content", "COMPLETENESS", "BLOCKER", "题面为空", "请填写题目描述与输入输出格式"));
        if (blank(source.getAnswer())) issues.add(issue("missing-answer", "SOLUTION", "MAJOR", "标准答案为空", "无法检查题解正确性"));
        if (source.getJudgeCase() == null || source.getJudgeCase().isEmpty()) {
            issues.add(issue("missing-cases", "TEST_CASES", "BLOCKER", "测试用例为空", "至少需要一组输入输出"));
        }
        if (source.getJudgeConfig() == null) {
            issues.add(issue("missing-config", "JUDGE_CONFIG", "MAJOR", "JudgeConfig 为空", "请设置时间、内存与栈限制"));
        }
        if (!blank(source.getContent())) {
            for (String section : List.of("题目描述", "输入格式", "输出格式", "数据范围")) {
                if (!source.getContent().contains(section)) {
                    issues.add(issue("section-" + section, "COMPLETENESS", "MINOR",
                            "缺少“" + section + "”章节", "题面 Markdown 结构不完整"));
                }
            }
        }
        return issues;
    }

    private List<QualityPatch> duplicatePatches(ProblemSourceDraft source, List<QualityIssue> issues) {
        List<QualityPatch> patches = new ArrayList<>();
        if (source == null || source.getJudgeCase() == null) return patches;
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < source.getJudgeCase().size(); index++) {
            GeneratedJudgeCase judgeCase = source.getJudgeCase().get(index);
            String key = normalize(judgeCase.getInput()) + "\u0000" + normalize(judgeCase.getOutput());
            if (!seen.add(key)) {
                String issueId = "duplicate-case-" + index;
                issues.add(issue(issueId, "TEST_CASES", "MINOR", "存在重复测试用例", "用例 " + (index + 1) + " 与之前用例完全重复"));
                patches.add(casePatch("REMOVE_DUPLICATE_CASE", "/judgeCase/" + index,
                        judgeCase, null, judgeCase, "删除完全重复的测试用例", List.of(issueId)));
            }
        }
        return patches;
    }

    private void addExecutionIssuesAndPatches(ProblemSourceDraft source,
                                               BatchVerificationResult baseline,
                                               List<QualityIssue> issues,
                                               List<QualityPatch> patches) {
        if (!baseline.rejected().isEmpty()) {
            issues.add(issue("case-validation-failed", "TEST_CASES", "BLOCKER",
                    "已有用例未通过独立程序校验", "输入校验、双解或 Oracle 证据不一致"));
        }
        Map<String, VerifiedCandidate> byDigest = new LinkedHashMap<>();
        baseline.accepted().forEach(item -> byDigest.put(item.evidence().getInputDigest(), item));
        for (int index = 0; index < source.getJudgeCase().size(); index++) {
            GeneratedJudgeCase judgeCase = source.getJudgeCase().get(index);
            VerifiedCandidate verified = baseline.accepted().stream()
                    .filter(item -> normalize(item.candidate().getInput()).equals(normalize(judgeCase.getInput())))
                    .findFirst().orElse(null);
            if (verified == null) continue;
            CaseEvidence evidence = verified.evidence();
            evidence.setCaseIndex(index);
            evidence.setExpectedOutput(normalize(judgeCase.getOutput()));
            boolean tripleAgreement = evidence.isCrossLanguageMatched()
                    && evidence.getOracleOutput() != null && evidence.isOracleMatched();
            if (!normalize(judgeCase.getOutput()).equals(normalize(verified.output()))) {
                String issueId = "wrong-output-" + index;
                issues.add(issue(issueId, "TEST_CASES", "BLOCKER", "预期输出与独立解不一致",
                        "用例 " + (index + 1) + " 的 Java、C++ 与 Oracle 均输出 " + verified.output()));
                if (tripleAgreement) {
                    patches.add(casePatch("UPDATE_CASE_OUTPUT", "/judgeCase/" + index + "/output",
                            judgeCase.getOutput(), verified.output(), judgeCase,
                            "三方独立执行结果一致", List.of(issueId)));
                }
            }
        }
    }

    private void mergeModelReview(ProblemSourceDraft source,
                                  QualityReviewState state,
                                  QualityModelReview review,
                                  List<QualityIssue> issues,
                                  List<QualityPatch> patches) {
        if (review == null) return;
        if (review.getIssues() != null) {
            for (QualityIssue issue : review.getIssues()) {
                issue.setDimension(normalizeEnum(issue.getDimension(), DIMENSIONS, "CONSISTENCY"));
                issue.setSeverity(normalizeEnum(issue.getSeverity(), SEVERITIES, "INFO"));
                if (blank(issue.getId())) issue.setId("semantic-" + (issues.size() + 1));
                if (!blank(issue.getTitle())) issues.add(issue);
            }
        }
        if (review.getPatchSuggestions() != null) {
            for (QualityPatchSuggestion suggestion : review.getPatchSuggestions()) {
                if (suggestion == null || !PATCH_TARGETS.contains(suggestion.getTarget())) continue;
                Object before = valueAt(source, suggestion.getTarget());
                if (before == null || suggestion.getAfterValue() == null) continue;
                patches.add(patch("REPLACE_FIELD", suggestion.getTarget(), before,
                        suggestion.getAfterValue(), suggestion.getReason(), List.of()));
            }
        }
        if (Boolean.TRUE.equals(review.getAnswerNeedsReplacement()) && !blank(review.getCanonicalExplanation())) {
            ReferenceSolution java = state.getSolutions().stream()
                    .filter(solution -> "java".equals(solution.getLanguage())).findFirst().orElse(null);
            if (java != null) {
                String replacement = review.getCanonicalExplanation() + "\n\n## Java 参考实现\n\n```java\n"
                        + java.getCode() + "\n```\n";
                patches.add(patch("REPLACE_FIELD", "/answer", source.getAnswer(), replacement,
                        "修订后的主参考代码已编译并通过双解与 Oracle 交叉验证", List.of()));
            }
        }
    }

    private QualityReport report(boolean complete, List<QualityIssue> issues, QualityVerificationSummary summary) {
        QualityScorer.ScoreResult score = QualityScorer.score(issues, complete);
        QualityReport report = new QualityReport();
        report.setComplete(complete);
        report.setTotalScore(score.totalScore());
        report.setDimensions(score.dimensions());
        report.setIssues(issues);
        report.setVerification(summary);
        return report;
    }

    private QualityVerificationSummary verificationSummary(ProblemSourceDraft source, BatchVerificationResult baseline) {
        QualityVerificationSummary summary = new QualityVerificationSummary();
        summary.setSemanticReviewed(true);
        summary.setCheckSolutionsCompiled(true);
        summary.setCrossLanguageMatched(baseline.rejected().isEmpty());
        summary.setTotalCases(source.getJudgeCase().size());
        summary.setVerifiedCases(baseline.accepted().size());
        summary.setOracleCases(baseline.oracleCases());
        return summary;
    }

    private List<CaseEvidence> evidence(ProblemSourceDraft source, BatchVerificationResult baseline) {
        List<CaseEvidence> result = new ArrayList<>();
        for (VerifiedCandidate candidate : baseline.accepted()) {
            CaseEvidence evidence = candidate.evidence();
            int index = indexOfInput(source, candidate.candidate().getInput());
            evidence.setCaseIndex(index);
            if (index >= 0) evidence.setExpectedOutput(source.getJudgeCase().get(index).getOutput());
            result.add(evidence);
        }
        return result;
    }

    private List<CaseEvidence> inspectEvidence(List<Integer> indexes, List<CaseEvidence> evidence) {
        Set<Integer> requested = new HashSet<>(indexes);
        return evidence.stream().filter(item -> requested.contains(item.getCaseIndex())).toList();
    }

    private boolean isComplete(ProblemSourceDraft source) {
        return source != null && !blank(source.getTitle()) && !blank(source.getContent())
                && !blank(source.getAnswer()) && source.getJudgeConfig() != null
                && source.getJudgeCase() != null && !source.getJudgeCase().isEmpty();
    }

    private GeneratedProblemSpec fromSource(ProblemSourceDraft source) {
        GeneratedProblemSpec spec = new GeneratedProblemSpec();
        spec.setTitle(source.getTitle());
        spec.setContent(source.getContent());
        spec.setDifficulty(source.getDifficulty());
        spec.setTags(source.getTags());
        spec.setSolutionExplanation(source.getAnswer());
        spec.setJudgeConfig(source.getJudgeConfig());
        return spec;
    }

    private ReferenceSolution solution(GeneratedProblemSpec spec, String language) {
        ReferenceSolution solution = structuredModel.generateReferenceSolution(spec, language);
        if (solution == null || blank(solution.getCode())) {
            throw new IllegalStateException(language + " 质检校验解为空");
        }
        solution.setLanguage(language);
        return solution;
    }

    private List<CandidateTestInput> candidates(ProblemSourceDraft source) {
        return source.getJudgeCase().stream().map(item -> {
            CandidateTestInput candidate = new CandidateTestInput();
            candidate.setInput(item.getInput());
            candidate.setCategory(item.getCategory());
            OracleEligibilityPolicy.enforce(candidate);
            return candidate;
        }).toList();
    }

    private QualityIssue issue(String id, String dimension, String severity, String title, String detail) {
        QualityIssue issue = new QualityIssue();
        issue.setId(id); issue.setDimension(dimension); issue.setSeverity(severity);
        issue.setTitle(title); issue.setDetail(detail);
        return issue;
    }

    private QualityPatch patch(String operation, String target, Object before, Object after,
                               String reason, List<String> evidenceRefs) {
        QualityPatch patch = new QualityPatch();
        patch.setId("patch-" + Math.abs((operation + target + String.valueOf(after)).hashCode()));
        patch.setOperation(operation); patch.setTarget(target);
        patch.setBeforeValue(before); patch.setAfterValue(after);
        patch.setBeforeHash(DraftFingerprint.value(before, objectMapper));
        patch.setReason(blank(reason) ? "AI 质检修改建议" : reason);
        patch.setEvidenceRefs(evidenceRefs);
        return patch;
    }

    private QualityPatch casePatch(String operation, String target, Object before, Object after,
                                   GeneratedJudgeCase judgeCase, String reason, List<String> evidenceRefs) {
        QualityPatch patch = patch(operation, target, before, after, reason, evidenceRefs);
        patch.setCaseInputHash(DraftFingerprint.value(normalize(judgeCase.getInput()), objectMapper));
        patch.setCaseOutputHash(DraftFingerprint.value(normalize(judgeCase.getOutput()), objectMapper));
        return patch;
    }

    private Object valueAt(ProblemSourceDraft source, String target) {
        return switch (target) {
            case "/title" -> source.getTitle();
            case "/content" -> source.getContent();
            case "/difficulty" -> source.getDifficulty();
            case "/tags" -> source.getTags();
            case "/judgeConfig/timeLimit" -> config(source).getTimeLimit();
            case "/judgeConfig/memoryLimit" -> config(source).getMemoryLimit();
            case "/judgeConfig/stackLimit" -> config(source).getStackLimit();
            default -> null;
        };
    }

    private JudgeConfigValue config(ProblemSourceDraft source) {
        return source.getJudgeConfig() == null ? new JudgeConfigValue() : source.getJudgeConfig();
    }

    private int indexOfInput(ProblemSourceDraft source, String input) {
        for (int index = 0; index < source.getJudgeCase().size(); index++) {
            if (normalize(input).equals(normalize(source.getJudgeCase().get(index).getInput()))) return index;
        }
        return -1;
    }

    private String normalizeEnum(String value, Set<String> allowed, String fallback) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        return allowed.contains(normalized) ? normalized : fallback;
    }

    private String normalize(String value) { return value == null ? "" : value.replace("\r\n", "\n").stripTrailing(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}

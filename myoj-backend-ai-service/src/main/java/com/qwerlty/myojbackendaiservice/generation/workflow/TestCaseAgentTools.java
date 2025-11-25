package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateEvaluationResult;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateRejection;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CoverageRisk;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStage;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class TestCaseAgentTools {
    private static final int MAX_ROUNDS = 8;
    private static final int MAX_BATCH_SIZE = 10;
    private static final int MAX_INPUT_BYTES = 1024 * 1024;

    private final WorkflowContext context;
    private final SandboxBatchVerifier verifier;
    private final TestCaseGenerationState state;
    private final int targetCount;

    public TestCaseAgentTools(WorkflowContext context,
                              SandboxBatchVerifier verifier,
                              TestCaseGenerationState state,
                              int targetCount) {
        this.context = context;
        this.verifier = verifier;
        this.state = state;
        this.targetCount = targetCount;
    }

    @Tool(description = "提交候选测试输入并获得代码沙箱验收结果、拒绝原因、剩余数量和覆盖缺口。每次最多10项。")
    public CandidateEvaluationResult evaluateCandidateCases(
            @ToolParam(description = "候选输入列表，不得包含期望输出，最多10项") List<CandidateTestInput> candidates) {
        context.checkCancelled();
        if (state.getRounds() >= MAX_ROUNDS) {
            throw new GenerationValidationException("测试用例 Agent 已达到 8 轮工具调用上限");
        }
        if (candidates == null || candidates.isEmpty() || candidates.size() > MAX_BATCH_SIZE) {
            throw new GenerationValidationException("每轮必须提交 1 到 10 个候选测试输入");
        }
        state.setRounds(state.getRounds() + 1);
        long started = System.nanoTime();
        List<CandidateRejection> preliminary = new ArrayList<>();
        List<CandidateTestInput> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        state.getAcceptedCases().forEach(item -> seen.add(normalizeInput(item.getCandidate().getInput())));
        for (CandidateTestInput candidate : candidates) {
            if (candidate == null || candidate.getInput() == null || candidate.getInput().isBlank()) {
                preliminary.add(new CandidateRejection("empty", "输入为空"));
                continue;
            }
            candidate.setInput(normalizeInput(candidate.getInput()));
            candidate.setCategory(normalizeCategory(candidate.getCategory()));
            candidate.setRiskIds(candidate.getRiskIds() == null ? List.of() : candidate.getRiskIds());
            if (candidate.getInput().getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
                preliminary.add(new CandidateRejection("oversize", "单个输入超过 1 MiB"));
            } else if (!seen.add(candidate.getInput())) {
                preliminary.add(new CandidateRejection("duplicate", "输入与已验收或本轮候选重复"));
            } else if (state.getAcceptedCases().size() + unique.size() < targetCount) {
                unique.add(candidate);
            }
        }
        BatchVerificationResult verification = verifier.verify(unique, state.getSolutions(),
                state.getPrograms(), config());
        verification.accepted().forEach(item -> state.getAcceptedCases().add(
                new AcceptedCaseState(item.candidate(), item.output(), item.evidence())));
        List<CandidateRejection> rejections = new ArrayList<>(preliminary);
        rejections.addAll(verification.rejected());
        state.setRejectedCount(state.getRejectedCount() + rejections.size());
        int acceptedThisRound = verification.accepted().size();
        long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        context.recordToolCall(new ToolCallTrace(state.getRounds(), "evaluateCandidateCases",
                candidates.size(), acceptedThisRound, rejections.size(), latency,
                state.getAcceptedCases().size() >= targetCount ? "TARGET_REACHED" : "CONTINUE"));
        context.meterRegistry().counter("ai_authoring_tool_calls_total",
                "type", context.taskType().name(),
                "tool", "evaluateCandidateCases",
                "round", Integer.toString(state.getRounds()),
                "accepted", Integer.toString(acceptedThisRound),
                "rejected", Integer.toString(rejections.size())).increment();
        context.checkpoint(GenerationStage.AGENT_GENERATING_CASES, state);
        return new CandidateEvaluationResult(state.getRounds(), candidates.size(), acceptedThisRound,
                rejections.size(), state.getAcceptedCases().size(),
                Math.max(0, targetCount - state.getAcceptedCases().size()), categoryCounts(),
                uncoveredRiskIds(), rejections);
    }

    public List<String> uncoveredRiskIds() {
        Set<String> covered = new LinkedHashSet<>();
        state.getAcceptedCases().forEach(item -> covered.addAll(item.getCandidate().getRiskIds()));
        return (state.getCoveragePlan() == null || state.getCoveragePlan().getDynamicRisks() == null
                ? List.<CoverageRisk>of() : state.getCoveragePlan().getDynamicRisks()).stream()
                .map(CoverageRisk::getId).filter(id -> id != null && !covered.contains(id)).toList();
    }

    public Map<String, Integer> categoryCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String category : List.of("NORMAL", "BOUNDARY", "MAXIMUM", "ADVERSARIAL")) counts.put(category, 0);
        state.getAcceptedCases().forEach(item -> counts.merge(
                normalizeCategory(item.getCandidate().getCategory()), 1, Integer::sum));
        return counts;
    }

    private JudgeConfigValue config() {
        return state.getSpecification().getJudgeConfig() == null
                ? new JudgeConfigValue() : state.getSpecification().getJudgeConfig();
    }

    private String normalizeInput(String value) { return value.replace("\r\n", "\n"); }
    private String normalizeCategory(String value) {
        String normalized = value == null ? "NORMAL" : value.trim().toUpperCase(Locale.ROOT);
        return Set.of("NORMAL", "BOUNDARY", "MAXIMUM", "ADVERSARIAL", "EXAMPLE").contains(normalized)
                ? normalized : "NORMAL";
    }
}

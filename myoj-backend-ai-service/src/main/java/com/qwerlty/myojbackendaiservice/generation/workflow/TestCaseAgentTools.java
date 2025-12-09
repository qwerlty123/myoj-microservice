package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateInputChunk;
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
    public static final List<String> REQUIRED_CATEGORIES =
            List.of("NORMAL", "BOUNDARY", "MAXIMUM", "ADVERSARIAL");
    private static final int MAX_ROUNDS = 8;
    private static final int MAX_BATCH_SIZE = 10;
    private static final int MAX_INPUT_BYTES = 1024 * 1024;
    private static final int MAX_DIRECT_INPUT_BYTES = 8 * 1024;
    private static final int MAX_TOOL_DESCRIPTOR_BYTES = 32 * 1024;
    private static final int MAX_CHUNKS = 32;
    private static final int MAX_EXPANSION_ITEMS = 1_000_000;

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

    @Tool(description = "提交候选测试输入并获得沙箱验收结果。每次最多10项，工具参数JSON总计不得超过32KiB；"
            + "input只用于8KiB以内的小输入，大输入必须用LITERAL/REPEAT/RANGE/CYCLE chunks压缩表示；"
            + "category必须是NORMAL/BOUNDARY/MAXIMUM/ADVERSARIAL，并覆盖全部四类。")
    public CandidateEvaluationResult evaluateCandidateCases(
            @ToolParam(description = "候选输入列表，不得包含期望输出，最多10项；每项的input与chunks必须二选一")
            List<CandidateTestInput> candidates) {
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
        int descriptorBytes = 0;
        state.getAcceptedCases().forEach(item -> seen.add(normalizeInput(item.getCandidate().getInput())));
        for (CandidateTestInput candidate : candidates) {
            if (candidate == null) {
                preliminary.add(new CandidateRejection("empty", "输入为空"));
                continue;
            }
            String category = parseCategory(candidate.getCategory());
            if (category == null) {
                preliminary.add(new CandidateRejection("category",
                        "category 必须是 NORMAL、BOUNDARY、MAXIMUM、ADVERSARIAL 之一"));
                continue;
            }
            candidate.setCategory(category);
            try {
                int candidateDescriptorBytes = descriptorBytes(candidate);
                if (candidateDescriptorBytes > MAX_TOOL_DESCRIPTOR_BYTES - descriptorBytes) {
                    throw new CandidateInputException("encoding", "本轮工具参数超过 32 KiB，请使用压缩 chunks");
                }
                descriptorBytes += candidateDescriptorBytes;
                candidate.setInput(materializeInput(candidate));
                candidate.setChunks(List.of());
                OracleEligibilityPolicy.enforce(candidate);
            } catch (CandidateInputException exception) {
                preliminary.add(new CandidateRejection(exception.code, exception.getMessage()));
                continue;
            }
            if (candidate.getInput().isBlank()) {
                preliminary.add(new CandidateRejection("empty", "输入为空"));
                continue;
            }
            candidate.setInput(normalizeInput(candidate.getInput()));
            candidate.setRiskIds(candidate.getRiskIds() == null ? List.of() : candidate.getRiskIds());
            if (candidate.getInput().getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
                preliminary.add(new CandidateRejection("oversize", "单个输入超过 1 MiB"));
            } else if (seen.contains(candidate.getInput())) {
                preliminary.add(new CandidateRejection("duplicate", "输入与已验收或本轮候选重复"));
            } else if (state.getAcceptedCases().size() + unique.size() < targetCount) {
                if (preservesRequiredCategoryCapacity(candidate.getCategory(), unique)) {
                    seen.add(candidate.getInput());
                    unique.add(candidate);
                } else {
                    preliminary.add(new CandidateRejection("category_capacity",
                            "必须为缺失类别预留名额: " + String.join(", ", missingAfter(unique))));
                }
            } else {
                preliminary.add(new CandidateRejection("capacity", "已达到目标数量，不再接收额外候选"));
            }
        }
        BatchVerificationResult verification;
        try {
            verification = verifier.verify(unique, state.getSolutions(), state.getPrograms(), config());
        } catch (RuntimeException exception) {
            recordToolError(candidates.size(), preliminary.size(), started, exception);
            throw exception;
        }
        verification.accepted().forEach(item -> state.getAcceptedCases().add(
                new AcceptedCaseState(item.candidate(), item.output(), item.evidence())));
        List<CandidateRejection> rejections = new ArrayList<>(preliminary);
        rejections.addAll(verification.rejected());
        state.setRejectedCount(state.getRejectedCount() + rejections.size());
        int acceptedThisRound = verification.accepted().size();
        long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        boolean targetReached = targetReached();
        context.recordToolCall(new ToolCallTrace(state.getRounds(), "evaluateCandidateCases",
                candidates.size(), acceptedThisRound, rejections.size(), latency,
                targetReached ? "TARGET_REACHED" : "CONTINUE"));
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
                missingRequiredCategories(), uncoveredRiskIds(), rejections);
    }

    public boolean hasRemainingRounds() {
        return state.getRounds() < MAX_ROUNDS;
    }

    public int reopenSlotsForMissingCategories() {
        if (targetCount < REQUIRED_CATEGORIES.size()) {
            throw new GenerationValidationException("目标用例数量不能小于必选类别数量");
        }
        int removed = 0;
        boolean oraclePolicyRepaired = false;
        for (AcceptedCaseState acceptedCase : state.getAcceptedCases()) {
            CandidateTestInput candidate = acceptedCase.getCandidate();
            Boolean before = candidate.getOracleEligible();
            OracleEligibilityPolicy.enforce(candidate);
            oraclePolicyRepaired |= !java.util.Objects.equals(before, candidate.getOracleEligible());
        }
        Map<String, Integer> counts = categoryCounts();
        while (targetCount - state.getAcceptedCases().size() < missingRequiredCategories().size()) {
            int removable = findRemovableCase(counts);
            if (removable < 0) break;
            AcceptedCaseState discarded = state.getAcceptedCases().remove(removable);
            counts.computeIfPresent(storedCategory(discarded.getCandidate().getCategory()),
                    (category, count) -> Math.max(0, count - 1));
            removed++;
        }
        if (removed > 0) {
            state.setRejectedCount(state.getRejectedCount() + removed);
            context.meterRegistry().counter("ai_authoring_category_repairs_total",
                    "type", context.taskType().name()).increment(removed);
        }
        if (removed > 0 || oraclePolicyRepaired) {
            context.checkpoint(GenerationStage.AGENT_GENERATING_CASES, state);
        }
        return removed;
    }

    public List<String> missingRequiredCategories() {
        Map<String, Integer> counts = categoryCounts();
        return REQUIRED_CATEGORIES.stream()
                .filter(category -> counts.getOrDefault(category, 0) == 0)
                .toList();
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
        for (String category : REQUIRED_CATEGORIES) counts.put(category, 0);
        state.getAcceptedCases().forEach(item -> counts.merge(
                storedCategory(item.getCandidate().getCategory()), 1, Integer::sum));
        return counts;
    }

    private boolean preservesRequiredCategoryCapacity(String category, List<CandidateTestInput> pending) {
        Set<String> covered = new LinkedHashSet<>();
        state.getAcceptedCases().forEach(item -> covered.add(storedCategory(item.getCandidate().getCategory())));
        pending.forEach(item -> covered.add(storedCategory(item.getCategory())));
        covered.add(category);
        int acceptedAfter = state.getAcceptedCases().size() + pending.size() + 1;
        long missingAfter = REQUIRED_CATEGORIES.stream().filter(required -> !covered.contains(required)).count();
        return targetCount - acceptedAfter >= missingAfter;
    }

    private List<String> missingAfter(List<CandidateTestInput> pending) {
        Set<String> covered = new LinkedHashSet<>();
        state.getAcceptedCases().forEach(item -> covered.add(storedCategory(item.getCandidate().getCategory())));
        pending.forEach(item -> covered.add(storedCategory(item.getCategory())));
        return REQUIRED_CATEGORIES.stream().filter(category -> !covered.contains(category)).toList();
    }

    private int findRemovableCase(Map<String, Integer> counts) {
        for (int index = state.getAcceptedCases().size() - 1; index >= 0; index--) {
            String category = storedCategory(state.getAcceptedCases().get(index).getCandidate().getCategory());
            if (!REQUIRED_CATEGORIES.contains(category) || counts.getOrDefault(category, 0) > 1) {
                return index;
            }
        }
        return -1;
    }

    private boolean targetReached() {
        return state.getAcceptedCases().size() >= targetCount && missingRequiredCategories().isEmpty();
    }

    private void recordToolError(int submitted, int rejected, long started, RuntimeException exception) {
        long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        String errorType = exception.getClass().getSimpleName();
        context.recordToolCall(new ToolCallTrace(state.getRounds(), "evaluateCandidateCases",
                submitted, 0, rejected, latency, "TOOL_ERROR", errorType, safeSummary(exception)));
        context.meterRegistry().counter("ai_authoring_tool_failures_total",
                "type", context.taskType().name(),
                "tool", "evaluateCandidateCases",
                "error", errorType).increment();
        context.checkpoint(GenerationStage.AGENT_GENERATING_CASES, state);
    }

    private String safeSummary(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        String singleLine = message.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 240 ? singleLine : singleLine.substring(0, 240);
    }

    private JudgeConfigValue config() {
        return state.getSpecification().getJudgeConfig() == null
                ? new JudgeConfigValue() : state.getSpecification().getJudgeConfig();
    }

    private int descriptorBytes(CandidateTestInput candidate) {
        long bytes = utf8Bytes(candidate.getInput()) + utf8Bytes(candidate.getCategory());
        if (candidate.getRiskIds() != null) {
            for (String riskId : candidate.getRiskIds()) bytes += utf8Bytes(riskId);
        }
        List<CandidateInputChunk> chunks = candidate.getChunks();
        if (chunks != null) {
            for (CandidateInputChunk chunk : chunks) {
                if (chunk == null) continue;
                bytes += utf8Bytes(chunk.getType()) + utf8Bytes(chunk.getValue())
                        + utf8Bytes(chunk.getSeparator());
                if (chunk.getValues() != null) {
                    for (String value : chunk.getValues()) bytes += utf8Bytes(value);
                }
                if (bytes > MAX_TOOL_DESCRIPTOR_BYTES) return MAX_TOOL_DESCRIPTOR_BYTES + 1;
            }
        }
        return (int) bytes;
    }

    private String materializeInput(CandidateTestInput candidate) {
        boolean hasInput = candidate.getInput() != null && !candidate.getInput().isEmpty();
        boolean hasChunks = candidate.getChunks() != null && !candidate.getChunks().isEmpty();
        if (hasInput == hasChunks) {
            throw new CandidateInputException("encoding", "input 与 chunks 必须二选一");
        }
        if (hasInput) {
            if (utf8Bytes(candidate.getInput()) > MAX_DIRECT_INPUT_BYTES) {
                throw new CandidateInputException("encoding", "直接 input 超过 8 KiB，请使用压缩 chunks");
            }
            return candidate.getInput();
        }
        if (candidate.getChunks().size() > MAX_CHUNKS) {
            throw new CandidateInputException("encoding", "chunks 不能超过 32 个片段");
        }

        LimitedInputBuilder result = new LimitedInputBuilder();
        for (CandidateInputChunk chunk : candidate.getChunks()) {
            appendChunk(result, chunk);
        }
        return result.toString();
    }

    private void appendChunk(LimitedInputBuilder result, CandidateInputChunk chunk) {
        if (chunk == null || chunk.getType() == null) {
            throw new CandidateInputException("encoding", "chunk.type 不能为空");
        }
        String type = chunk.getType().trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "LITERAL" -> result.append(requireValue(chunk.getValue(), "LITERAL.value"));
            case "REPEAT" -> appendRepeat(result, requireValue(chunk.getValue(), "REPEAT.value"),
                    count(chunk), separator(chunk, ""));
            case "RANGE" -> appendRange(result, chunk);
            case "CYCLE" -> appendCycle(result, chunk);
            default -> throw new CandidateInputException("encoding", "不支持的 chunk.type: " + type);
        }
    }

    private void appendRepeat(LimitedInputBuilder result, String value, int count, String separator) {
        for (int index = 0; index < count; index++) {
            if (index > 0) result.append(separator);
            result.append(value);
        }
    }

    private void appendRange(LimitedInputBuilder result, CandidateInputChunk chunk) {
        if (chunk.getStart() == null) {
            throw new CandidateInputException("encoding", "RANGE.start 不能为空");
        }
        long step = chunk.getStep() == null ? 1L : chunk.getStep();
        int count = count(chunk);
        String separator = separator(chunk, " ");
        for (int index = 0; index < count; index++) {
            if (index > 0) result.append(separator);
            try {
                result.append(Long.toString(Math.addExact(chunk.getStart(), Math.multiplyExact(step, index))));
            } catch (ArithmeticException exception) {
                throw new CandidateInputException("encoding", "RANGE 计算结果超出 long 范围");
            }
        }
    }

    private void appendCycle(LimitedInputBuilder result, CandidateInputChunk chunk) {
        List<String> values = chunk.getValues();
        if (values == null || values.isEmpty() || values.size() > 100) {
            throw new CandidateInputException("encoding", "CYCLE.values 必须包含 1 到 100 个短文本");
        }
        if (values.stream().anyMatch(value -> value == null || value.isEmpty())) {
            throw new CandidateInputException("encoding", "CYCLE.values 不能包含空文本");
        }
        int count = count(chunk);
        String separator = separator(chunk, " ");
        for (int index = 0; index < count; index++) {
            if (index > 0) result.append(separator);
            result.append(values.get(index % values.size()));
        }
    }

    private int count(CandidateInputChunk chunk) {
        if (chunk.getCount() == null || chunk.getCount() <= 0 || chunk.getCount() > MAX_EXPANSION_ITEMS) {
            throw new CandidateInputException("encoding", "chunk.count 必须在 1 到 1000000 之间");
        }
        return chunk.getCount();
    }

    private String requireValue(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new CandidateInputException("encoding", field + " 不能为空");
        }
        return value;
    }

    private String separator(CandidateInputChunk chunk, String defaultValue) {
        return chunk.getSeparator() == null ? defaultValue : chunk.getSeparator();
    }

    private int utf8Bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static final class LimitedInputBuilder {
        private final StringBuilder value = new StringBuilder();
        private int bytes;

        private void append(String fragment) {
            int fragmentBytes = fragment.getBytes(StandardCharsets.UTF_8).length;
            if (fragmentBytes > MAX_INPUT_BYTES - bytes) {
                throw new CandidateInputException("oversize", "压缩输入展开后超过 1 MiB");
            }
            value.append(fragment);
            bytes += fragmentBytes;
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    private static final class CandidateInputException extends RuntimeException {
        private final String code;

        private CandidateInputException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private String normalizeInput(String value) { return value.replace("\r\n", "\n"); }
    private String parseCategory(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return REQUIRED_CATEGORIES.contains(normalized) ? normalized : null;
    }
    private String storedCategory(String value) {
        String normalized = parseCategory(value);
        return normalized == null ? "NORMAL" : normalized;
    }
}

package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.generation.sandbox.AuthoringSandboxVerifier;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationPurpose;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationReport;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.DraftPatchResult;
import com.qwerlty.myojbackendaiservice.model.dto.generation.DraftRepairOperation;
import com.qwerlty.myojbackendaiservice.model.dto.generation.DraftRepairPatch;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStage;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class ProblemDraftTools {
    private static final int MAX_CALLS = 3;
    private static final int MAX_OPERATIONS = 3;
    private static final int MAX_PATCH_BYTES = 512 * 1024;
    private static final Set<String> STRING_SPEC_TARGETS = Set.of(
            "/spec/title", "/spec/content", "/spec/solutionExplanation");
    private static final Set<String> SOLUTION_TARGETS = Set.of(
            "/solutions/java", "/solutions/cpp", "/solutions/go");
    private static final Set<String> PROGRAM_TARGETS = Set.of(
            "/programs/validatorJava", "/programs/oracleJava");

    private final WorkflowContext context;
    private final AuthoringSandboxVerifier verifier;
    private final ProblemDraftWorkflow.DraftState state;
    private final ObjectMapper objectMapper;

    public ProblemDraftTools(WorkflowContext context,
                             AuthoringSandboxVerifier verifier,
                             ProblemDraftWorkflow.DraftState state,
                             ObjectMapper objectMapper) {
        this.context = context;
        this.verifier = verifier;
        this.state = state;
        this.objectMapper = objectMapper;
        state.setStateHash(stateHash(state, objectMapper));
    }

    @Tool(description = "对当前题目草稿应用白名单局部补丁并调用可信沙箱验证。"
            + "每次最多3个替换操作，baseHash必须等于当前stateHash；规格补丁不能与代码补丁混用。")
    public DraftPatchResult verifyDraftPatch(
            @ToolParam(description = "基于当前stateHash的替换补丁，只能修改允许的target") DraftRepairPatch patch) {
        context.authorizeTool("verifyDraftPatch");
        if (state.getRepairCalls() >= MAX_CALLS) {
            throw new GenerationValidationException("题目草稿 Agent 已达到 3 次工具调用上限");
        }
        if (state.getSolutions().isEmpty() || state.getPrograms() == null) {
            throw new GenerationValidationException("规格已修改，必须先由工作流重新生成下游产物");
        }
        validateEnvelope(patch);
        if (!state.getStateHash().equals(patch.getBaseHash())) {
            throw new GenerationValidationException("题目草稿补丁基于过期状态");
        }
        boolean specificationPatch = patch.getOperations().stream()
                .anyMatch(operation -> operation != null && isSpecificationTarget(operation.getTarget()));
        boolean downstreamPatch = patch.getOperations().stream()
                .anyMatch(operation -> operation != null && !isSpecificationTarget(operation.getTarget()));
        if (specificationPatch && downstreamPatch) {
            throw new GenerationValidationException("规格补丁不能与代码或验证程序补丁混用");
        }
        if (specificationPatch && state.getSpecificationRepairCalls() >= 1) {
            throw new GenerationValidationException("题目规格最多允许修复一次");
        }

        ProblemDraftWorkflow.DraftState working = copy(state);
        Set<String> targets = new HashSet<>();
        for (DraftRepairOperation operation : patch.getOperations()) {
            if (operation == null || !targets.add(operation.getTarget())) {
                throw new GenerationValidationException("补丁操作为空或包含重复 target");
            }
            apply(working, operation);
        }
        validateState(working);

        long started = System.nanoTime();
        state.setRepairCalls(state.getRepairCalls() + 1);
        state.setSpecification(working.getSpecification());
        state.setSolutions(working.getSolutions());
        state.setPrograms(working.getPrograms());
        if (specificationPatch) {
            state.setSpecificationRepairCalls(state.getSpecificationRepairCalls() + 1);
            state.setSolutions(new ArrayList<>());
            state.setPrograms(null);
            verifier.invalidateTask(context.taskId());
            state.setLastVerificationSummary("规格已更新，必须重新生成全部下游产物");
            state.setStateHash(stateHash(state, objectMapper));
            context.checkpoint(GenerationStage.VERIFYING_SAMPLES, state);
            record(patch, started, "REGENERATION_REQUIRED", 0);
            return result("REGENERATION_REQUIRED", List.of());
        }

        state.setStateHash(stateHash(state, objectMapper));
        context.checkpoint(GenerationStage.VERIFYING_SAMPLES, state);
        try {
            VerificationReport report = verifier.verify(new VerificationRequest(
                    VerificationPurpose.DRAFT_REPAIR,
                    ProblemDraftWorkflow.candidates(state.getSpecification()), state.getSolutions(),
                    state.getPrograms(), ProblemDraftWorkflow.normalizedConfig(state.getSpecification())));
            state.setLastVerificationSummary(report.summary());
            state.setStateHash(stateHash(state, objectMapper));
            context.checkpoint(GenerationStage.VERIFYING_SAMPLES, state);
            String status = report.passed() ? "PASSED" : "REPAIRABLE";
            record(patch, started, status, report.issues().size());
            return result(status, report.issues());
        } catch (ResourceAccessException | RejectedExecutionException exception) {
            state.setRepairCalls(state.getRepairCalls() - 1);
            state.setStateHash(stateHash(state, objectMapper));
            context.checkpoint(GenerationStage.VERIFYING_SAMPLES, state);
            record(patch, started, "DEPENDENCY_FAILURE", 1);
            throw exception;
        }
    }

    public boolean hasRemainingCalls() {
        return state.getRepairCalls() < MAX_CALLS;
    }

    public int remainingCalls() {
        return Math.max(0, MAX_CALLS - state.getRepairCalls());
    }

    public String currentStateHash() {
        state.setStateHash(stateHash(state, objectMapper));
        return state.getStateHash();
    }

    private void validateEnvelope(DraftRepairPatch patch) {
        if (patch == null || blank(patch.getBaseHash()) || patch.getOperations() == null
                || patch.getOperations().isEmpty() || patch.getOperations().size() > MAX_OPERATIONS) {
            throw new GenerationValidationException("补丁必须包含 baseHash 和 1 到 3 个操作");
        }
        try {
            if (objectMapper.writeValueAsBytes(patch).length > MAX_PATCH_BYTES) {
                throw new GenerationValidationException("题目草稿补丁超过 512 KiB");
            }
        } catch (JsonProcessingException exception) {
            throw new GenerationValidationException("题目草稿补丁无法序列化");
        }
    }

    private void apply(ProblemDraftWorkflow.DraftState targetState, DraftRepairOperation operation) {
        String target = operation.getTarget();
        Object value = operation.getAfterValue();
        if (STRING_SPEC_TARGETS.contains(target)) {
            String text = requiredString(value, target);
            if ("/spec/title".equals(target)) targetState.getSpecification().setTitle(text);
            if ("/spec/content".equals(target)) targetState.getSpecification().setContent(text);
            if ("/spec/solutionExplanation".equals(target)) {
                targetState.getSpecification().setSolutionExplanation(text);
            }
            return;
        }
        if ("/spec/difficulty".equals(target)) {
            if (!(value instanceof Number number) || number.intValue() < 0 || number.intValue() > 2) {
                throw new GenerationValidationException("difficulty 必须是 0、1、2");
            }
            targetState.getSpecification().setDifficulty(number.intValue());
            return;
        }
        if ("/spec/tags".equals(target)) {
            if (!(value instanceof List<?> values) || values.size() > 10
                    || values.stream().anyMatch(item -> !(item instanceof String text) || blank(text))) {
                throw new GenerationValidationException("tags 必须是不超过 10 项的非空字符串列表");
            }
            targetState.getSpecification().setTags(values.stream().map(String.class::cast).toList());
            return;
        }
        if ("/spec/judgeConfig".equals(target)) {
            rejectUnknownObjectFields(value,
                    Set.of("timeLimit", "memoryLimit", "stackLimit"), target);
            targetState.getSpecification().setJudgeConfig(objectMapper.convertValue(value, JudgeConfigValue.class));
            return;
        }
        if (target != null && target.startsWith("/spec/sampleInputs/")) {
            int index = sampleIndex(target);
            if (index < 0 || index >= targetState.getSpecification().getSampleInputs().size()) {
                throw new GenerationValidationException("样例补丁下标越界");
            }
            rejectUnknownObjectFields(value,
                    Set.of("category", "input", "oracleEligible"), target);
            GeneratedTestInput input = objectMapper.convertValue(value, GeneratedTestInput.class);
            if (input == null || blank(input.getInput())) {
                throw new GenerationValidationException("样例输入不能为空");
            }
            input.setOracleEligible(true);
            targetState.getSpecification().getSampleInputs().set(index, input);
            return;
        }
        if (SOLUTION_TARGETS.contains(target)) {
            String language = target.substring("/solutions/".length());
            replaceSolution(targetState, language, requiredString(value, target));
            return;
        }
        if (PROGRAM_TARGETS.contains(target)) {
            if (targetState.getPrograms() == null) targetState.setPrograms(new ValidationPrograms());
            String code = requiredString(value, target);
            if ("/programs/validatorJava".equals(target)) targetState.getPrograms().setValidatorJava(code);
            if ("/programs/oracleJava".equals(target)) targetState.getPrograms().setOracleJava(code);
            return;
        }
        throw new GenerationValidationException("不允许修改题目草稿字段: " + target);
    }

    private void validateState(ProblemDraftWorkflow.DraftState candidate) {
        GeneratedProblemSpec spec = candidate.getSpecification();
        if (spec == null || blank(spec.getTitle()) || blank(spec.getContent())
                || blank(spec.getSolutionExplanation())) {
            throw new GenerationValidationException("题目规格缺少必要字段");
        }
        if (spec.getSampleInputs() == null || spec.getSampleInputs().size() < 2
                || spec.getSampleInputs().size() > 3
                || spec.getSampleInputs().stream().anyMatch(item -> item == null || blank(item.getInput()))) {
            throw new GenerationValidationException("题目草稿必须包含 2 到 3 个有效样例输入");
        }
        JudgeConfigValue config = spec.getJudgeConfig();
        if (config == null || config.getTimeLimit() == null || config.getMemoryLimit() == null
                || config.getStackLimit() == null
                || config.getTimeLimit() < 100 || config.getTimeLimit() > 15000
                || config.getMemoryLimit() < 16384 || config.getMemoryLimit() > 524288
                || config.getStackLimit() < 256 || config.getStackLimit() > 262144) {
            throw new GenerationValidationException("JudgeConfig 超出允许范围");
        }
    }

    private void replaceSolution(ProblemDraftWorkflow.DraftState targetState, String language, String code) {
        List<ReferenceSolution> updated = new ArrayList<>(targetState.getSolutions());
        ReferenceSolution replacement = new ReferenceSolution();
        replacement.setLanguage(language);
        replacement.setCode(code);
        updated.removeIf(solution -> solution != null && language.equals(solution.getLanguage()));
        updated.add(replacement);
        targetState.setSolutions(updated);
    }

    private int sampleIndex(String target) {
        try {
            return Integer.parseInt(target.substring("/spec/sampleInputs/".length()));
        } catch (RuntimeException exception) {
            throw new GenerationValidationException("样例 target 必须以数字下标结尾");
        }
    }

    private ProblemDraftWorkflow.DraftState copy(ProblemDraftWorkflow.DraftState source) {
        return objectMapper.convertValue(objectMapper.valueToTree(source), ProblemDraftWorkflow.DraftState.class);
    }

    private boolean isSpecificationTarget(String target) {
        return target != null && target.startsWith("/spec/");
    }

    private String requiredString(Object value, String target) {
        if (!(value instanceof String text) || blank(text)) {
            throw new GenerationValidationException(target + " 必须是非空字符串");
        }
        return text;
    }

    private void rejectUnknownObjectFields(Object value, Set<String> allowedFields, String target) {
        if (!(value instanceof Map<?, ?> fields)) return;
        for (Object field : fields.keySet()) {
            if (!(field instanceof String name) || !allowedFields.contains(name)) {
                throw new GenerationValidationException(target + " 包含未知字段: " + field);
            }
        }
    }

    private DraftPatchResult result(String status,
                                    List<com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationIssue> issues) {
        return new DraftPatchResult(status, state.getStateHash(), state.getLastVerificationSummary(),
                remainingCalls(), List.copyOf(issues));
    }

    private void record(DraftRepairPatch patch, long started, String outcome, int rejected) {
        long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        context.recordToolCall(new ToolCallTrace(state.getRepairCalls(), "verifyDraftPatch",
                patch.getOperations().size(), "PASSED".equals(outcome) ? patch.getOperations().size() : 0,
                rejected, latency, outcome));
        context.meterRegistry().counter("ai_authoring_tool_calls_total",
                "type", context.taskType().name(), "tool", "verifyDraftPatch",
                "round", Integer.toString(state.getRepairCalls()), "outcome", outcome).increment();
    }

    public static String stateHash(ProblemDraftWorkflow.DraftState state, ObjectMapper objectMapper) {
        return DraftFingerprint.value(java.util.Arrays.asList(
                state.getSpecification(), state.getSolutions(), state.getPrograms()), objectMapper);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

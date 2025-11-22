package com.qwerlty.myojbackendaiservice.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedJudgeCase;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemDraft;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GenerationArtifact;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GenerationRequirements;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GenerationTaskCreateRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GenerationValidationReport;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemSourceDraft;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.TestInputPlan;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationMode;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStage;
import com.qwerlty.myojbackendaiservice.sandbox.CodeSandboxClient;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecuteResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ProblemGenerationEngine {

    private static final List<String> LANGUAGES = List.of("java", "cpp", "go");
    private static final Set<String> CATEGORIES = Set.of(
            "EXAMPLE", "NORMAL", "BOUNDARY", "MAXIMUM", "ADVERSARIAL");
    private static final int MAX_TOTAL_INPUT_BYTES = 1024 * 1024;
    private static final int MAX_JUDGE_CASE_JSON_BYTES = 1024 * 1024;
    private static final int MAX_ANSWER_BYTES = 200 * 1024;

    private final ProblemGenerationModel model;
    private final CodeSandboxClient sandboxClient;
    private final ObjectMapper objectMapper;

    public ProblemGenerationEngine(ProblemGenerationModel model,
                                   CodeSandboxClient sandboxClient,
                                   ObjectMapper objectMapper) {
        this.model = model;
        this.sandboxClient = sandboxClient;
        this.objectMapper = objectMapper;
    }

    public GenerationArtifact generate(GenerationTaskCreateRequest request,
                                       GenerationProgressListener progressListener) {
        return generate(null, request, progressListener);
    }

    public GenerationArtifact generate(Long taskId,
                                       GenerationTaskCreateRequest request,
                                       GenerationProgressListener progressListener) {
        GenerationMode mode = parseMode(request.getMode());
        GenerationRequirements requirements = request.getRequirements();
        long started = System.nanoTime();
        log.info("[AI_GENERATION] engine started taskId={} mode={} requestedCases={}",
                taskId, mode, requirements.getCaseCount());

        progressListener.onStage(GenerationStage.GENERATING_SPEC);
        GeneratedProblemSpec specification = mode == GenerationMode.FULL_PROBLEM
                ? modelCall(taskId, "generate_specification",
                () -> model.generateSpecification(requirements))
                : fromSource(request.getSourceDraft());
        validateSpecification(specification);
        log.info("[AI_GENERATION] specification validated taskId={} mode={} difficulty={} tagCount={}",
                taskId, mode, specification.getDifficulty(), specification.getTags().size());

        progressListener.onStage(GenerationStage.GENERATING_SOLUTIONS);
        List<ReferenceSolution> solutions = LANGUAGES.stream()
                .map(language -> modelCall(taskId, "generate_solution_" + language,
                        () -> model.generateReferenceSolution(specification, language)))
                .collect(Collectors.toList());
        validateSolutions(solutions);
        log.info("[AI_GENERATION] reference solutions validated taskId={} languages={}",
                taskId, LANGUAGES);
        ValidationPrograms validationPrograms = modelCall(taskId, "generate_validation_programs",
                () -> model.generateValidationPrograms(specification));
        if (validationPrograms == null
                || !StringUtils.hasText(validationPrograms.getValidatorJava())
                || !StringUtils.hasText(validationPrograms.getOracleJava())) {
            throw new GenerationValidationException("模型未生成完整的输入校验器和暴力解");
        }
        log.info("[AI_GENERATION] validation programs validated taskId={}", taskId);

        progressListener.onStage(GenerationStage.GENERATING_CASES);
        TestInputPlan inputPlan = modelCall(taskId, "generate_test_inputs",
                () -> model.generateTestInputs(specification, requirements));
        List<GeneratedTestInput> inputs = normalizeInputs(inputPlan, requirements.getCaseCount());
        log.info("[AI_GENERATION] test inputs normalized taskId={} caseCount={}",
                taskId, inputs.size());

        progressListener.onStage(GenerationStage.COMPILING);
        JudgeConfigValue judgeConfig = normalizedJudgeConfig(specification.getJudgeConfig());
        List<String> rawInputs = inputs.stream().map(GeneratedTestInput::getInput).toList();
        SandboxExecuteResponse validator = execute(taskId, "input_validator", "java",
                validationPrograms.getValidatorJava(), rawInputs, judgeConfig);
        requireSuccessful(validator, "输入校验器");
        if (validator.getOutputList().size() != rawInputs.size()
                || validator.getOutputList().stream().anyMatch(value -> !"VALID".equals(value))) {
            throw new GenerationValidationException("至少一组生成输入未通过格式和范围校验");
        }

        Map<String, SandboxExecuteResponse> executions = new LinkedHashMap<>();
        for (ReferenceSolution solution : solutions) {
            SandboxExecuteResponse response = execute(taskId,
                    "reference_solution_" + solution.getLanguage(),
                    solution.getLanguage(), solution.getCode(), rawInputs, judgeConfig);
            requireSuccessful(response, solution.getLanguage() + " 参考实现");
            if (response.getOutputList().size() != rawInputs.size()) {
                throw new GenerationValidationException(solution.getLanguage() + " 输出数量不正确");
            }
            executions.put(solution.getLanguage(), response);
        }

        progressListener.onStage(GenerationStage.CROSS_VALIDATING);
        List<String> canonicalOutputs = executions.get("java").getOutputList();
        for (String language : List.of("cpp", "go")) {
            if (!canonicalOutputs.equals(executions.get(language).getOutputList())) {
                throw new GenerationValidationException("Java、C++、Go 参考实现输出不一致");
            }
        }

        List<Integer> oracleIndexes = new ArrayList<>();
        List<String> oracleInputs = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            if (Boolean.TRUE.equals(inputs.get(index).getOracleEligible())) {
                oracleIndexes.add(index);
                oracleInputs.add(inputs.get(index).getInput());
            }
        }
        if (oracleInputs.size() < 3) {
            throw new GenerationValidationException("至少需要三组可由暴力解校验的小规模用例");
        }
        SandboxExecuteResponse oracle = execute(taskId, "brute_force_oracle", "java",
                validationPrograms.getOracleJava(), oracleInputs, relaxedOracleConfig(judgeConfig));
        requireSuccessful(oracle, "小数据暴力解");
        if (oracle.getOutputList().size() != oracleInputs.size()) {
            throw new GenerationValidationException("暴力解输出数量不正确");
        }
        for (int index = 0; index < oracleIndexes.size(); index++) {
            if (!canonicalOutputs.get(oracleIndexes.get(index)).equals(oracle.getOutputList().get(index))) {
                throw new GenerationValidationException("参考实现与小数据暴力解输出不一致");
            }
        }

        progressListener.onStage(GenerationStage.QUALITY_CHECKING);
        GenerationValidationReport report = report(inputs, oracleInputs.size());
        GeneratedProblemDraft draft = draft(specification, solutions, inputs, canonicalOutputs, judgeConfig);
        validatePersistenceLimits(draft);
        log.info("[AI_GENERATION] engine completed taskId={} caseCount={} oracleCases={} qualityScore={} latencyMs={}",
                taskId, inputs.size(), oracleInputs.size(), report.getQualityScore(), elapsedMillis(started));
        return new GenerationArtifact(draft, report);
    }

    private GeneratedProblemSpec fromSource(ProblemSourceDraft source) {
        if (source == null || !StringUtils.hasText(source.getTitle())
                || !StringUtils.hasText(source.getContent())) {
            throw new GenerationValidationException("生成测试用例需要完整的题目草稿");
        }
        GeneratedProblemSpec specification = new GeneratedProblemSpec();
        specification.setTitle(source.getTitle());
        specification.setContent(source.getContent());
        specification.setDifficulty(source.getDifficulty() == null ? 1 : source.getDifficulty());
        specification.setTags(source.getTags() == null ? new ArrayList<>() : source.getTags());
        specification.setSolutionExplanation(StringUtils.hasText(source.getAnswer())
                ? source.getAnswer() : "请根据题面推导正确算法。");
        specification.setJudgeConfig(source.getJudgeConfig());
        return specification;
    }

    private void validateSpecification(GeneratedProblemSpec specification) {
        if (specification == null || !StringUtils.hasText(specification.getTitle())
                || !StringUtils.hasText(specification.getContent())
                || !StringUtils.hasText(specification.getSolutionExplanation())) {
            throw new GenerationValidationException("题目规格缺少必要字段");
        }
        if (specification.getTitle().length() > 80 || specification.getContent().length() > 8192) {
            throw new GenerationValidationException("生成的题目标题或题面过长");
        }
        if (specification.getDifficulty() == null || specification.getDifficulty() < 0
                || specification.getDifficulty() > 2) {
            throw new GenerationValidationException("题目难度不合法");
        }
        List<String> tags = specification.getTags() == null ? Collections.emptyList() : specification.getTags();
        specification.setTags(tags.stream().filter(StringUtils::hasText).distinct().limit(10).toList());
    }

    private void validateSolutions(List<ReferenceSolution> solutions) {
        if (solutions.size() != LANGUAGES.size()) {
            throw new GenerationValidationException("参考实现数量不正确");
        }
        for (int index = 0; index < solutions.size(); index++) {
            ReferenceSolution solution = solutions.get(index);
            String expectedLanguage = LANGUAGES.get(index);
            if (solution == null || !StringUtils.hasText(solution.getCode())) {
                throw new GenerationValidationException(expectedLanguage + " 参考实现为空");
            }
            solution.setLanguage(expectedLanguage);
            if (solution.getCode().getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) {
                throw new GenerationValidationException(expectedLanguage + " 参考实现过长");
            }
        }
    }

    private List<GeneratedTestInput> normalizeInputs(TestInputPlan plan, Integer requestedCount) {
        if (plan == null || plan.getInputs() == null) {
            throw new GenerationValidationException("模型未生成测试输入");
        }
        Map<String, GeneratedTestInput> unique = new LinkedHashMap<>();
        int totalBytes = 0;
        for (GeneratedTestInput item : plan.getInputs()) {
            if (item == null || item.getInput() == null) {
                continue;
            }
            String input = item.getInput().replace("\r\n", "\n");
            int bytes = input.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 1024 * 1024) {
                throw new GenerationValidationException("单个测试输入超过 1 MiB");
            }
            totalBytes += bytes;
            item.setInput(input);
            item.setCategory(normalizeCategory(item.getCategory()));
            item.setOracleEligible(Boolean.TRUE.equals(item.getOracleEligible()));
            unique.putIfAbsent(input, item);
        }
        int expectedCount = requestedCount == null ? 20 : requestedCount;
        if (unique.size() != expectedCount) {
            throw new GenerationValidationException("去重后的测试用例数量必须等于 " + expectedCount);
        }
        if (totalBytes > MAX_TOTAL_INPUT_BYTES) {
            throw new GenerationValidationException("测试输入总大小超过 1 MiB");
        }
        return new ArrayList<>(unique.values());
    }

    private String normalizeCategory(String value) {
        String normalized = value == null ? "NORMAL" : value.trim().toUpperCase(Locale.ROOT);
        return CATEGORIES.contains(normalized) ? normalized : "NORMAL";
    }

    private SandboxExecuteResponse execute(Long taskId,
                                           String component,
                                           String language,
                                           String code,
                                           List<String> inputs,
                                           JudgeConfigValue config) {
        long started = System.nanoTime();
        log.info("[AI_GENERATION] sandbox call started taskId={} component={} language={} caseCount={} timeLimitMs={}",
                taskId, component, language, inputs.size(), config.getTimeLimit());
        try {
            SandboxExecuteResponse response = sandboxClient.execute(language, code, inputs,
                    config.getTimeLimit(), config.getMemoryLimit(), config.getStackLimit());
            log.info("[AI_GENERATION] sandbox call completed taskId={} component={} language={} status={} outputCount={} latencyMs={}",
                    taskId, component, language,
                    response == null ? null : response.getStatus(),
                    response == null || response.getOutputList() == null
                            ? null : response.getOutputList().size(),
                    elapsedMillis(started));
            return response;
        } catch (RuntimeException exception) {
            log.error("[AI_GENERATION] sandbox call failed taskId={} component={} language={} errorType={} latencyMs={}",
                    taskId, component, language, exception.getClass().getSimpleName(),
                    elapsedMillis(started), exception);
            throw exception;
        }
    }

    private <T> T modelCall(Long taskId, String operation, Supplier<T> supplier) {
        long started = System.nanoTime();
        log.info("[AI_GENERATION] model call started taskId={} operation={}", taskId, operation);
        try {
            T result = supplier.get();
            log.info("[AI_GENERATION] model call completed taskId={} operation={} latencyMs={}",
                    taskId, operation, elapsedMillis(started));
            return result;
        } catch (RuntimeException exception) {
            log.error("[AI_GENERATION] model call failed taskId={} operation={} errorType={} latencyMs={}",
                    taskId, operation, exception.getClass().getSimpleName(),
                    elapsedMillis(started), exception);
            throw exception;
        }
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private void requireSuccessful(SandboxExecuteResponse response, String name) {
        if (response == null || !Integer.valueOf(1).equals(response.getStatus())) {
            String message = response == null ? "无响应" : response.getMessage();
            throw new GenerationValidationException(name + "沙箱验证失败: "
                    + (StringUtils.hasText(message) ? message : "未知错误"));
        }
    }

    private JudgeConfigValue normalizedJudgeConfig(JudgeConfigValue value) {
        JudgeConfigValue result = value == null ? new JudgeConfigValue() : value;
        if (result.getTimeLimit() == null) result.setTimeLimit(1000L);
        if (result.getMemoryLimit() == null) result.setMemoryLimit(262144L);
        if (result.getStackLimit() == null) result.setStackLimit(65536L);
        result.setTimeLimit(Math.max(100L, Math.min(15000L, result.getTimeLimit())));
        result.setMemoryLimit(Math.max(16384L, Math.min(524288L, result.getMemoryLimit())));
        result.setStackLimit(Math.max(256L, Math.min(262144L, result.getStackLimit())));
        return result;
    }

    private JudgeConfigValue relaxedOracleConfig(JudgeConfigValue source) {
        JudgeConfigValue config = new JudgeConfigValue();
        config.setTimeLimit(Math.min(15000L, Math.max(5000L, source.getTimeLimit() * 3L)));
        config.setMemoryLimit(source.getMemoryLimit());
        config.setStackLimit(source.getStackLimit());
        return config;
    }

    private GenerationValidationReport report(List<GeneratedTestInput> inputs, int oracleCases) {
        GenerationValidationReport report = new GenerationValidationReport();
        report.setCompiledLanguages(new ArrayList<>(LANGUAGES));
        report.setCrossLanguageMatched(true);
        report.setValidatorPassed(true);
        report.setOracleMatched(true);
        report.setTotalCases(inputs.size());
        report.setOracleCases(oracleCases);
        report.setDuplicateCases(0);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String category : CATEGORIES) {
            counts.put(category, 0);
        }
        for (GeneratedTestInput input : inputs) {
            counts.compute(input.getCategory(), (key, count) -> count == null ? 1 : count + 1);
        }
        report.setCategoryCounts(counts);
        int score = 100;
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() == 0) {
                score -= 8;
                warnings.add("缺少 " + entry.getKey() + " 类测试用例");
            }
        }
        if (oracleCases < Math.max(3, inputs.size() / 3)) {
            score -= 10;
            warnings.add("可由暴力解交叉验证的小规模用例偏少");
        }
        report.setQualityScore(Math.max(0, score));
        report.setWarnings(warnings);
        return report;
    }

    private GeneratedProblemDraft draft(GeneratedProblemSpec specification,
                                        List<ReferenceSolution> solutions,
                                        List<GeneratedTestInput> inputs,
                                        List<String> outputs,
                                        JudgeConfigValue judgeConfig) {
        List<GeneratedJudgeCase> judgeCases = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            judgeCases.add(new GeneratedJudgeCase(
                    inputs.get(index).getInput(), outputs.get(index), inputs.get(index).getCategory()));
        }
        GeneratedProblemDraft draft = new GeneratedProblemDraft();
        draft.setTitle(specification.getTitle());
        draft.setContent(specification.getContent());
        draft.setDifficulty(specification.getDifficulty());
        draft.setTags(specification.getTags());
        draft.setReferenceSolutions(solutions);
        draft.setJudgeCase(judgeCases);
        draft.setJudgeConfig(judgeConfig);
        draft.setAnswer(answer(specification.getSolutionExplanation(), solutions));
        return draft;
    }

    private String answer(String explanation, List<ReferenceSolution> solutions) {
        StringBuilder answer = new StringBuilder(explanation.trim());
        for (ReferenceSolution solution : solutions) {
            answer.append("\n\n## ").append(displayLanguage(solution.getLanguage()))
                    .append(" 参考实现\n\n```").append(solution.getLanguage()).append("\n")
                    .append(solution.getCode().trim()).append("\n```");
        }
        return answer.toString();
    }

    private void validatePersistenceLimits(GeneratedProblemDraft draft) {
        if (draft.getAnswer().getBytes(StandardCharsets.UTF_8).length > MAX_ANSWER_BYTES) {
            throw new GenerationValidationException("题解和三语言参考实现总大小超过 200 KiB");
        }
        try {
            if (objectMapper.writeValueAsBytes(draft.getJudgeCase()).length > MAX_JUDGE_CASE_JSON_BYTES) {
                throw new GenerationValidationException("判题用例 JSON 总大小超过 1 MiB");
            }
        } catch (JsonProcessingException exception) {
            throw new GenerationValidationException("判题用例无法序列化");
        }
    }

    private String displayLanguage(String value) {
        return switch (value) {
            case "java" -> "Java";
            case "cpp" -> "C++";
            case "go" -> "Go";
            default -> value;
        };
    }

    private GenerationMode parseMode(String value) {
        try {
            return GenerationMode.valueOf(value);
        } catch (Exception exception) {
            throw new GenerationValidationException("不支持的生成模式");
        }
    }
}

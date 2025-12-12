package com.qwerlty.myojbackendaiservice.generation.sandbox;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.qwerlty.myojbackendaiservice.generation.AiCallContext;
import com.qwerlty.myojbackendaiservice.manager.DistributedLeaseManager;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateRejection;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CaseEvidence;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationLane;
import com.qwerlty.myojbackendaiservice.sandbox.CodeSandboxClient;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxCaseExecutionResult;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxConfigurationException;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecuteResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DefaultAuthoringSandboxVerifier implements AuthoringSandboxVerifier {
    private static final int STATUS_SUCCEEDED = 1;
    private static final int STATUS_SANDBOX_ERROR = 2;
    private static final int STATUS_USER_CODE_ERROR = 3;
    private static final String CONTRACT_VERSION = "authoring-sandbox-v1";

    private final CodeSandboxClient sandboxClient;
    private final DistributedLeaseManager leases;
    private final MeterRegistry meterRegistry;
    private final int publicConcurrency;
    private final int reviewConcurrency;
    private final Cache<ExecutionCacheKey, SandboxExecuteResponse> cache;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntil = new AtomicLong();

    public DefaultAuthoringSandboxVerifier(CodeSandboxClient sandboxClient) {
        this(sandboxClient, null, new SimpleMeterRegistry(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Autowired
    public DefaultAuthoringSandboxVerifier(
            CodeSandboxClient sandboxClient,
            DistributedLeaseManager leases,
            MeterRegistry meterRegistry,
            @Value("${myoj.ai.generation.sandbox-concurrency.public:2}") int publicConcurrency,
            @Value("${myoj.ai.generation.sandbox-concurrency.review:1}") int reviewConcurrency) {
        this.sandboxClient = sandboxClient;
        this.leases = leases;
        this.meterRegistry = meterRegistry;
        this.publicConcurrency = Math.max(1, publicConcurrency);
        this.reviewConcurrency = Math.max(1, reviewConcurrency);
        this.cache = Caffeine.newBuilder().maximumSize(1024).expireAfterWrite(Duration.ofMinutes(30)).build();
    }

    @Override
    public VerificationReport verify(VerificationRequest request) {
        VerificationPurpose purpose = request == null || request.purpose() == null
                ? VerificationPurpose.CASE_ACCEPTANCE : request.purpose();
        List<CandidateTestInput> candidates = request == null || request.candidates() == null
                ? List.of() : request.candidates();
        List<ReferenceSolution> solutions = request == null || request.solutions() == null
                ? List.of() : request.solutions();
        ValidationPrograms programs = request == null ? null : request.programs();
        JudgeConfigValue judgeConfig = request == null || request.judgeConfig() == null
                ? new JudgeConfigValue() : request.judgeConfig();
        if (candidates.isEmpty()) return report(purpose, List.of(), List.of(), 0, List.of());

        List<VerificationIssue> issues = new ArrayList<>();
        if (programs == null || blank(programs.getValidatorJava())) {
            issues.add(issue(VerificationIssueCode.MISSING_ARTIFACT, "/programs/validatorJava", "java",
                    null, null, "缺少输入校验器", null));
        }
        if (programs == null || blank(programs.getOracleJava())) {
            issues.add(issue(VerificationIssueCode.MISSING_ARTIFACT, "/programs/oracleJava", "java",
                    null, null, "缺少小数据 Oracle", null));
        }
        Map<String, ReferenceSolution> byLanguage = new LinkedHashMap<>();
        for (ReferenceSolution solution : solutions) {
            if (solution != null && !blank(solution.getLanguage())) byLanguage.put(solution.getLanguage(), solution);
        }
        for (String language : purpose.requiredLanguages()) {
            ReferenceSolution solution = byLanguage.get(language);
            if (solution == null || blank(solution.getCode())) {
                issues.add(issue(VerificationIssueCode.MISSING_ARTIFACT, "/solutions/" + language, language,
                        null, null, "缺少 " + language + " 参考实现", null));
            }
        }
        if (!issues.isEmpty()) return report(purpose, List.of(), List.of(), 0, issues);

        List<String> inputs = candidates.stream().map(item -> item == null ? "" : normalize(item.getInput())).toList();
        ExecutionResult validator = execute(purpose, "/programs/validatorJava", "java",
                programs.getValidatorJava(), inputs, judgeConfig);
        if (validator.issue() != null) return report(purpose, List.of(), List.of(), 0, List.of(validator.issue()));

        List<Integer> validIndexes = new ArrayList<>();
        List<CandidateRejection> rejected = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            String inputDigest = digest(inputs.get(index));
            if (index < validator.response().getOutputList().size()
                    && "VALID".equals(normalize(validator.response().getOutputList().get(index)))) {
                validIndexes.add(index);
            } else {
                rejected.add(new CandidateRejection(inputDigest, "输入未通过格式或范围校验"));
                issues.add(issue(VerificationIssueCode.VALIDATOR_REJECTED, "/spec/sampleInputs/" + index,
                        null, index, inputDigest, "输入未通过格式或范围校验", null));
            }
        }
        if (validIndexes.isEmpty()) return report(purpose, List.of(), rejected, 0, issues);

        List<String> validInputs = validIndexes.stream().map(inputs::get).toList();
        Map<String, List<String>> languageOutputs = new LinkedHashMap<>();
        for (String language : purpose.requiredLanguages()) {
            ReferenceSolution solution = byLanguage.get(language);
            ExecutionResult result = execute(purpose, "/solutions/" + language, language,
                    solution.getCode(), validInputs, judgeConfig);
            if (result.issue() != null) {
                issues.add(result.issue());
            } else {
                languageOutputs.put(language, result.response().getOutputList().stream().map(this::normalize).toList());
            }
        }
        if (languageOutputs.size() != purpose.requiredLanguages().size()) {
            return report(purpose, List.of(), rejected, 0, issues);
        }

        List<Integer> oraclePositions = new ArrayList<>();
        List<String> oracleInputs = new ArrayList<>();
        for (int position = 0; position < validIndexes.size(); position++) {
            CandidateTestInput candidate = candidates.get(validIndexes.get(position));
            if (candidate != null && Boolean.TRUE.equals(candidate.getOracleEligible())) {
                oraclePositions.add(position);
                oracleInputs.add(validInputs.get(position));
            }
        }
        Map<Integer, String> oracleOutputs = new LinkedHashMap<>();
        if (!oracleInputs.isEmpty()) {
            ExecutionResult oracle = execute(purpose, "/programs/oracleJava", "java",
                    programs.getOracleJava(), oracleInputs, relaxed(judgeConfig));
            if (oracle.issue() != null) {
                issues.add(oracle.issue());
                return report(purpose, List.of(), rejected, oracleInputs.size(), issues);
            }
            for (int index = 0; index < oraclePositions.size(); index++) {
                oracleOutputs.put(oraclePositions.get(index), normalize(oracle.response().getOutputList().get(index)));
            }
        }

        List<VerifiedCandidate> accepted = new ArrayList<>();
        List<String> javaOutputs = languageOutputs.get("java");
        for (int position = 0; position < validIndexes.size(); position++) {
            int originalIndex = validIndexes.get(position);
            CandidateTestInput candidate = candidates.get(originalIndex);
            String inputDigest = digest(validInputs.get(position));
            String canonical = javaOutputs.get(position);
            int outputIndex = position;
            boolean languagesMatch = languageOutputs.values().stream()
                    .allMatch(outputs -> outputIndex < outputs.size() && canonical.equals(outputs.get(outputIndex)));
            String oracleOutput = oracleOutputs.get(position);
            boolean oracleMatch = oracleOutput == null || canonical.equals(oracleOutput);
            if (!languagesMatch) {
                rejected.add(new CandidateRejection(inputDigest, "多语言校验解输出不一致"));
                issues.add(issue(VerificationIssueCode.CROSS_LANGUAGE_MISMATCH,
                        "/spec/sampleInputs/" + originalIndex, null, originalIndex, inputDigest,
                        "多语言校验解输出不一致", outputSummary(languageOutputs, position)));
                continue;
            }
            if (!oracleMatch) {
                rejected.add(new CandidateRejection(inputDigest, "校验解与小数据 Oracle 输出不一致"));
                issues.add(issue(VerificationIssueCode.ORACLE_MISMATCH,
                        "/spec/sampleInputs/" + originalIndex, null, originalIndex, inputDigest,
                        "校验解与小数据 Oracle 输出不一致",
                        truncate("java=" + canonical + ", oracle=" + oracleOutput, 240)));
                continue;
            }
            CaseEvidence evidence = new CaseEvidence();
            evidence.setCaseIndex(originalIndex);
            evidence.setInputDigest(inputDigest);
            evidence.setValidatorPassed(true);
            evidence.setJavaOutput(canonical);
            evidence.setCppOutput(valueAt(languageOutputs.get("cpp"), position));
            evidence.setGoOutput(valueAt(languageOutputs.get("go"), position));
            evidence.setOracleOutput(oracleOutput);
            evidence.setCrossLanguageMatched(true);
            evidence.setOracleMatched(true);
            accepted.add(new VerifiedCandidate(candidate, canonical, evidence));
        }
        return report(purpose, accepted, rejected, oracleInputs.size(), issues);
    }

    private ExecutionResult execute(VerificationPurpose purpose,
                                    String target,
                                    String language,
                                    String code,
                                    List<String> inputs,
                                    JudgeConfigValue config) {
        AiCallContext.Value callContext = AiCallContext.current();
        Long taskId = callContext == null ? 0L : callContext.taskId();
        ExecutionCacheKey key = new ExecutionCacheKey(taskId, purpose.name(), CONTRACT_VERSION, language,
                digest(code), digestInputs(inputs), config.getTimeLimit(),
                config.getMemoryLimit(), config.getStackLimit());
        if (!purpose.independentGate()) {
            SandboxExecuteResponse cached = cache.getIfPresent(key);
            if (cached != null) {
                counter("ai_authoring_sandbox_cache_total", purpose, "result", "hit");
                return classify(target, language, cached, inputs.size());
            }
            counter("ai_authoring_sandbox_cache_total", purpose, "result", "miss");
        } else {
            counter("ai_authoring_sandbox_cache_total", purpose, "result", "bypass");
        }
        if (System.currentTimeMillis() < openUntil.get()) {
            throw new ResourceAccessException("代码沙箱熔断中");
        }
        GenerationLane lane = callContext == null ? GenerationLane.PUBLIC_AUTHORING : callContext.lane();
        int limit = lane == GenerationLane.ADMIN_REVIEW ? reviewConcurrency : publicConcurrency;
        try (DistributedLeaseManager.Lease ignored = leases == null ? null : leases.acquire(
                "sandbox:" + lane.name(), limit, Duration.ofSeconds(150))) {
            SandboxExecuteResponse response = sandboxClient.execute(language, code, inputs,
                    config.getTimeLimit(), config.getMemoryLimit(), config.getStackLimit());
            consecutiveFailures.set(0);
            if (response != null && (Integer.valueOf(STATUS_SUCCEEDED).equals(response.getStatus())
                    || Integer.valueOf(STATUS_USER_CODE_ERROR).equals(response.getStatus()))) {
                if (!purpose.independentGate()) cache.put(key, response);
            }
            if (response != null && Integer.valueOf(STATUS_SANDBOX_ERROR).equals(response.getStatus())) {
                throw new ResourceAccessException("代码沙箱报告系统错误");
            }
            return classify(target, language, response, inputs.size());
        } catch (SandboxConfigurationException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            recordDependencyFailure();
            throw exception;
        } catch (RuntimeException exception) {
            recordDependencyFailure();
            throw exception;
        }
    }

    private ExecutionResult classify(String target,
                                     String language,
                                     SandboxExecuteResponse response,
                                     int expectedOutputs) {
        if (response == null || response.getStatus() == null) {
            return failed(issue(VerificationIssueCode.INVALID_SANDBOX_RESPONSE, target, language,
                    null, null, "沙箱返回为空", null));
        }
        if (Integer.valueOf(STATUS_USER_CODE_ERROR).equals(response.getStatus())) {
            VerificationIssueCode code = classifyUserCodeFailure(response);
            SandboxCaseExecutionResult failedCase = firstFailedCase(response);
            Integer caseIndex = failedCase == null ? null : failedCase.getIndex();
            String diagnostic = failedCase == null ? response.getMessage()
                    : firstNonBlank(failedCase.getError(), response.getMessage());
            return failed(issue(code, target, language, caseIndex, null,
                    safeMessage(response, code), truncate(diagnostic, 240)));
        }
        if (!Integer.valueOf(STATUS_SUCCEEDED).equals(response.getStatus())
                || response.getOutputList() == null || response.getOutputList().size() != expectedOutputs) {
            return failed(issue(VerificationIssueCode.INVALID_SANDBOX_RESPONSE, target, language,
                    null, null, "沙箱输出数量与输入不一致",
                    "status=" + response.getStatus() + ", outputs="
                            + (response.getOutputList() == null ? -1 : response.getOutputList().size())
                            + "/" + expectedOutputs));
        }
        return new ExecutionResult(response, null);
    }

    private VerificationIssueCode classifyUserCodeFailure(SandboxExecuteResponse response) {
        SandboxCaseExecutionResult failedCase = firstFailedCase(response);
        if (failedCase != null && Boolean.TRUE.equals(failedCase.getOutputLimitExceeded())) {
            return VerificationIssueCode.OUTPUT_LIMIT_EXCEEDED;
        }
        if (failedCase != null && Boolean.TRUE.equals(failedCase.getTimedOut())) {
            return VerificationIssueCode.TIME_LIMIT_EXCEEDED;
        }
        String judgeMessage = response.getJudgeInfo() == null ? "" : normalize(response.getJudgeInfo().getMessage());
        if (judgeMessage.contains("Compile")) return VerificationIssueCode.COMPILE_ERROR;
        if (judgeMessage.contains("Time Limit")) return VerificationIssueCode.TIME_LIMIT_EXCEEDED;
        if (judgeMessage.contains("Memory Limit")) return VerificationIssueCode.MEMORY_LIMIT_EXCEEDED;
        if (judgeMessage.contains("Output Limit")) return VerificationIssueCode.OUTPUT_LIMIT_EXCEEDED;
        return VerificationIssueCode.RUNTIME_ERROR;
    }

    private SandboxCaseExecutionResult firstFailedCase(SandboxExecuteResponse response) {
        if (response.getCaseResults() == null) return null;
        return response.getCaseResults().stream().filter(item -> item != null
                && (!Integer.valueOf(0).equals(item.getExitCode())
                || Boolean.TRUE.equals(item.getTimedOut())
                || Boolean.TRUE.equals(item.getOutputLimitExceeded()))).findFirst().orElse(null);
    }

    private VerificationReport report(VerificationPurpose purpose,
                                      List<VerifiedCandidate> accepted,
                                      List<CandidateRejection> rejected,
                                      int oracleCases,
                                      List<VerificationIssue> issues) {
        VerificationOutcome outcome = rejected.isEmpty() && issues.isEmpty()
                ? VerificationOutcome.PASSED : VerificationOutcome.REPAIRABLE;
        meterRegistry.counter("ai_authoring_sandbox_verifications_total",
                "purpose", purpose.name(), "outcome", outcome.name()).increment();
        for (VerificationIssue issue : issues) {
            meterRegistry.counter("ai_authoring_sandbox_issues_total",
                    "purpose", purpose.name(), "code", issue.code().name()).increment();
        }
        return new VerificationReport(outcome, List.copyOf(accepted), List.copyOf(rejected),
                oracleCases, List.copyOf(issues));
    }

    private void counter(String name, VerificationPurpose purpose, String key, String value) {
        meterRegistry.counter(name, "purpose", purpose.name(), key, value).increment();
    }

    private void recordDependencyFailure() {
        if (consecutiveFailures.incrementAndGet() >= 5) {
            openUntil.set(System.currentTimeMillis() + 30_000L);
        }
    }

    private VerificationIssue issue(VerificationIssueCode code, String target, String language,
                                    Integer caseIndex, String inputDigest, String message, String diagnostic) {
        return new VerificationIssue(code, target, language, caseIndex, inputDigest,
                truncate(message, 240), truncate(diagnostic, 240));
    }

    private ExecutionResult failed(VerificationIssue issue) {
        return new ExecutionResult(null, issue);
    }

    private JudgeConfigValue relaxed(JudgeConfigValue source) {
        JudgeConfigValue value = new JudgeConfigValue();
        value.setTimeLimit(Math.min(15000L, Math.max(5000L, source.getTimeLimit() * 3)));
        value.setMemoryLimit(source.getMemoryLimit());
        value.setStackLimit(source.getStackLimit());
        return value;
    }

    private String outputSummary(Map<String, List<String>> outputs, int index) {
        List<String> parts = new ArrayList<>();
        outputs.forEach((language, values) -> parts.add(language + "=" + valueAt(values, index)));
        return truncate(String.join(", ", parts), 240);
    }

    private String safeMessage(SandboxExecuteResponse response, VerificationIssueCode code) {
        String message = normalize(response.getMessage());
        return message.isBlank() ? code.name() : message;
    }

    private String firstNonBlank(String first, String second) {
        return !blank(first) ? first : second;
    }

    private String valueAt(List<String> values, int index) {
        return values == null || index >= values.size() ? null : values.get(index);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").stripTrailing();
    }

    private String truncate(String value, int limit) {
        if (value == null) return null;
        String singleLine = value.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= limit ? singleLine : singleLine.substring(0, limit);
    }

    private String digest(String value) {
        try {
            String normalized = value == null ? "" : value;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算执行摘要", exception);
        }
    }

    private String digestInputs(List<String> inputs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String input : inputs) {
                byte[] bytes = (input == null ? "" : input).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算输入集合摘要", exception);
        }
    }

    @Override
    public void invalidateTask(Long taskId) {
        if (taskId == null) return;
        cache.asMap().keySet().removeIf(key -> taskId.equals(key.taskId()));
    }

    @Override
    public boolean isCircuitOpen() {
        return System.currentTimeMillis() < openUntil.get();
    }

    private record ExecutionResult(SandboxExecuteResponse response, VerificationIssue issue) { }

    private record ExecutionCacheKey(
            Long taskId,
            String purpose,
            String contractVersion,
            String language,
            String codeHash,
            String inputsHash,
            Long timeLimit,
            Long memoryLimit,
            Long stackLimit) { }
}

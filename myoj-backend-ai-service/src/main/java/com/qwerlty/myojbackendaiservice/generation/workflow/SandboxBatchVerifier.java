package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateRejection;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CaseEvidence;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import com.qwerlty.myojbackendaiservice.sandbox.CodeSandboxClient;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecuteResponse;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SandboxBatchVerifier {
    private final CodeSandboxClient sandboxClient;

    public SandboxBatchVerifier(CodeSandboxClient sandboxClient) {
        this.sandboxClient = sandboxClient;
    }

    public BatchVerificationResult verify(List<CandidateTestInput> candidates,
                                          List<ReferenceSolution> solutions,
                                          ValidationPrograms programs,
                                          JudgeConfigValue judgeConfig) {
        if (candidates == null || candidates.isEmpty()) {
            return new BatchVerificationResult(List.of(), List.of(), 0);
        }
        if (programs == null || blank(programs.getValidatorJava()) || blank(programs.getOracleJava())) {
            throw new GenerationValidationException("缺少输入校验器或小数据 Oracle");
        }
        List<String> inputs = candidates.stream().map(CandidateTestInput::getInput).toList();
        SandboxExecuteResponse validator = execute("java", programs.getValidatorJava(), inputs, judgeConfig);
        List<Integer> validIndexes = new ArrayList<>();
        List<CandidateRejection> rejected = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            if (index < validator.getOutputList().size()
                    && "VALID".equals(normalize(validator.getOutputList().get(index)))) {
                validIndexes.add(index);
            } else {
                rejected.add(new CandidateRejection(digest(inputs.get(index)), "输入未通过格式或范围校验"));
            }
        }
        if (validIndexes.isEmpty()) {
            return new BatchVerificationResult(List.of(), rejected, 0);
        }
        List<String> validInputs = validIndexes.stream().map(inputs::get).toList();
        Map<String, List<String>> languageOutputs = new LinkedHashMap<>();
        for (ReferenceSolution solution : solutions) {
            if (solution == null || blank(solution.getLanguage()) || blank(solution.getCode())) {
                throw new GenerationValidationException("参考实现不完整");
            }
            SandboxExecuteResponse response = execute(solution.getLanguage(), solution.getCode(), validInputs, judgeConfig);
            languageOutputs.put(solution.getLanguage(), response.getOutputList().stream().map(this::normalize).toList());
        }
        List<String> javaOutputs = languageOutputs.get("java");
        if (javaOutputs == null) throw new GenerationValidationException("缺少 Java 校验解");

        List<Integer> oraclePositions = new ArrayList<>();
        List<String> oracleInputs = new ArrayList<>();
        for (int position = 0; position < validIndexes.size(); position++) {
            if (Boolean.TRUE.equals(candidates.get(validIndexes.get(position)).getOracleEligible())) {
                oraclePositions.add(position);
                oracleInputs.add(validInputs.get(position));
            }
        }
        Map<Integer, String> oracleOutputs = new LinkedHashMap<>();
        if (!oracleInputs.isEmpty()) {
            SandboxExecuteResponse oracle = execute("java", programs.getOracleJava(), oracleInputs, relaxed(judgeConfig));
            for (int index = 0; index < oraclePositions.size(); index++) {
                oracleOutputs.put(oraclePositions.get(index), normalize(oracle.getOutputList().get(index)));
            }
        }

        List<VerifiedCandidate> accepted = new ArrayList<>();
        for (int position = 0; position < validIndexes.size(); position++) {
            int originalIndex = validIndexes.get(position);
            int outputIndex = position;
            CandidateTestInput candidate = candidates.get(originalIndex);
            String canonical = javaOutputs.get(position);
            boolean languagesMatch = languageOutputs.values().stream()
                    .allMatch(outputs -> outputIndex < outputs.size() && canonical.equals(outputs.get(outputIndex)));
            String oracleOutput = oracleOutputs.get(position);
            boolean oracleMatch = oracleOutput == null || canonical.equals(oracleOutput);
            if (!languagesMatch) {
                rejected.add(new CandidateRejection(digest(candidate.getInput()), "Java/C++/Go 校验解输出不一致"));
                continue;
            }
            if (!oracleMatch) {
                rejected.add(new CandidateRejection(digest(candidate.getInput()), "校验解与小数据 Oracle 输出不一致"));
                continue;
            }
            CaseEvidence evidence = new CaseEvidence();
            evidence.setCaseIndex(originalIndex);
            evidence.setInputDigest(digest(candidate.getInput()));
            evidence.setValidatorPassed(true);
            evidence.setJavaOutput(canonical);
            evidence.setCppOutput(valueAt(languageOutputs.get("cpp"), position));
            evidence.setOracleOutput(oracleOutput);
            evidence.setCrossLanguageMatched(languagesMatch);
            evidence.setOracleMatched(oracleMatch);
            accepted.add(new VerifiedCandidate(candidate, canonical, evidence));
        }
        return new BatchVerificationResult(accepted, rejected, oracleInputs.size());
    }

    private SandboxExecuteResponse execute(String language, String code, List<String> inputs, JudgeConfigValue config) {
        SandboxExecuteResponse response = sandboxClient.execute(language, code, inputs,
                config.getTimeLimit(), config.getMemoryLimit(), config.getStackLimit());
        if (response == null || !Integer.valueOf(1).equals(response.getStatus())
                || response.getOutputList() == null || response.getOutputList().size() != inputs.size()) {
            throw new GenerationValidationException("代码沙箱批量验证失败");
        }
        return response;
    }

    private JudgeConfigValue relaxed(JudgeConfigValue source) {
        JudgeConfigValue value = new JudgeConfigValue();
        value.setTimeLimit(Math.min(15000L, Math.max(5000L, source.getTimeLimit() * 3)));
        value.setMemoryLimit(source.getMemoryLimit());
        value.setStackLimit(source.getStackLimit());
        return value;
    }

    private String valueAt(List<String> values, int index) {
        return values == null || index >= values.size() ? null : values.get(index);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String normalize(String value) { return value == null ? "" : value.replace("\r\n", "\n").stripTrailing(); }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算输入摘要", exception);
        }
    }
}

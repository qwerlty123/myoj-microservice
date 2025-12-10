package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedJudgeCase;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemSourceDraft;

import java.util.List;

/** 只覆盖会影响题意、标准答案或判题结果的字段。 */
public final class ExecutionFingerprint {
    private ExecutionFingerprint() { }

    public static String of(ProblemSourceDraft draft, ObjectMapper objectMapper) {
        JudgeConfigValue config = draft.getJudgeConfig();
        List<List<String>> cases = (draft.getJudgeCase() == null
                ? List.<GeneratedJudgeCase>of() : draft.getJudgeCase()).stream()
                .map(item -> List.of(normalize(item.getInput()), normalize(item.getOutput())))
                .toList();
        return DraftFingerprint.value(List.of(
                normalize(draft.getContent()),
                normalize(draft.getAnswer()),
                config == null ? List.of() : java.util.Arrays.asList(
                        config.getTimeLimit(), config.getMemoryLimit(), config.getStackLimit()),
                cases), objectMapper);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n");
    }
}

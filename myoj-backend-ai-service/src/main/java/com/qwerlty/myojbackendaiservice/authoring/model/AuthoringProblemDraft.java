package com.qwerlty.myojbackendaiservice.authoring.model;

import com.qwerlty.myojbackendaiservice.dto.JudgeCase;

import java.util.List;

public record AuthoringProblemDraft(
        String title,
        Integer difficulty,
        String content,
        List<String> tags,
        String answer,
        String referenceCode,
        List<JudgeCase> judgeCase,
        JudgeConfig judgeConfig
) {
    public AuthoringProblemDraft {
        tags = tags == null ? List.of() : List.copyOf(tags);
        judgeCase = judgeCase == null ? List.of() : List.copyOf(judgeCase);
        judgeConfig = judgeConfig == null ? JudgeConfig.defaults() : judgeConfig;
    }

    public record JudgeConfig(int timeLimit, int memoryLimit, int stackLimit) {
        public static JudgeConfig defaults() {
            return new JudgeConfig(1000, 262144, 262144);
        }
    }
}

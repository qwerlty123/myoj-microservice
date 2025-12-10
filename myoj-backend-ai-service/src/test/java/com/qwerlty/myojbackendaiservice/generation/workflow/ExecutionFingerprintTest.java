package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedJudgeCase;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemSourceDraft;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionFingerprintTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ignoresPresentationFieldsButInvalidatesEveryExecutionField() {
        ProblemSourceDraft baseline = draft();
        String hash = ExecutionFingerprint.of(baseline, mapper);

        ProblemSourceDraft presentationEdit = copy(baseline);
        presentationEdit.setTitle("新标题");
        presentationEdit.setDifficulty(2);
        presentationEdit.setTags(List.of("新标签"));
        assertThat(ExecutionFingerprint.of(presentationEdit, mapper)).isEqualTo(hash);

        ProblemSourceDraft contentEdit = copy(baseline);
        contentEdit.setContent("新题意");
        assertThat(ExecutionFingerprint.of(contentEdit, mapper)).isNotEqualTo(hash);

        ProblemSourceDraft answerEdit = copy(baseline);
        answerEdit.setAnswer("新答案");
        assertThat(ExecutionFingerprint.of(answerEdit, mapper)).isNotEqualTo(hash);

        ProblemSourceDraft configEdit = copy(baseline);
        configEdit.getJudgeConfig().setTimeLimit(2_000L);
        assertThat(ExecutionFingerprint.of(configEdit, mapper)).isNotEqualTo(hash);

        ProblemSourceDraft caseEdit = copy(baseline);
        caseEdit.getJudgeCase().get(0).setOutput("3\n");
        assertThat(ExecutionFingerprint.of(caseEdit, mapper)).isNotEqualTo(hash);
    }

    private ProblemSourceDraft draft() {
        ProblemSourceDraft draft = new ProblemSourceDraft();
        draft.setTitle("原标题");
        draft.setDifficulty(1);
        draft.setTags(List.of("数组"));
        draft.setContent("原题意");
        draft.setAnswer("原答案");
        JudgeConfigValue config = new JudgeConfigValue();
        config.setTimeLimit(1_000L);
        config.setMemoryLimit(262_144L);
        config.setStackLimit(65_536L);
        draft.setJudgeConfig(config);
        GeneratedJudgeCase judgeCase = new GeneratedJudgeCase();
        judgeCase.setInput("1\n");
        judgeCase.setOutput("2\n");
        draft.setJudgeCase(List.of(judgeCase));
        return draft;
    }

    private ProblemSourceDraft copy(ProblemSourceDraft value) {
        return mapper.convertValue(value, ProblemSourceDraft.class);
    }
}

package com.qwerlty.myojbackendjudgeservice.judge.strategy;

import com.qwerlty.myojbackendmodel.model.codesandbox.JudgeInfo;
import com.qwerlty.myojbackendmodel.model.dto.question.JudgeCase;
import com.qwerlty.myojbackendmodel.model.entity.Question;
import com.qwerlty.myojbackendmodel.model.enums.JudgeInfoMessageEnum;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JudgeStrategyTest {

    @Test
    void javaExecutionTimeIsComparedDirectlyWithTheQuestionLimit() {
        JudgeContext context = context(1_500L, 0L, 1_000L, 256_000L);

        JudgeInfo result = new JavaLanguageJudgeStrategy().doJudge(context);

        assertEquals(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue(), result.getMessage());
    }

    @Test
    void defaultStrategyHandlesMissingSandboxMetrics() {
        JudgeContext context = context(null, null, 1_000L, 256_000L);

        JudgeInfo result = new DefaultJudgeStrategy().doJudge(context);

        assertEquals(JudgeInfoMessageEnum.ACCEPTED.getValue(), result.getMessage());
        assertEquals(0L, result.getTime());
        assertEquals(0L, result.getMemory());
    }

    private JudgeContext context(Long time, Long memory, Long timeLimit, Long memoryLimit) {
        JudgeInfo sandboxJudgeInfo = new JudgeInfo();
        sandboxJudgeInfo.setTime(time);
        sandboxJudgeInfo.setMemory(memory);

        JudgeCase judgeCase = new JudgeCase();
        judgeCase.setInput("21");
        judgeCase.setOutput("42");

        Question question = new Question();
        question.setJudgeConfig("{\"timeLimit\":" + timeLimit + ",\"memoryLimit\":" + memoryLimit + "}");

        JudgeContext context = new JudgeContext();
        context.setJudgeInfo(sandboxJudgeInfo);
        context.setInputList(Collections.singletonList("21"));
        context.setOutputList(Collections.singletonList("42"));
        context.setJudgeCaseList(Collections.singletonList(judgeCase));
        context.setQuestion(question);
        return context;
    }
}

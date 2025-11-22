package com.qwerlty.myojbackendquestionservice.service.impl;

import com.qwerlty.myojbackendcommon.exception.BusinessException;
import com.qwerlty.myojbackendmodel.model.entity.Question;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class QuestionServiceImplValidationTest {

    private QuestionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new QuestionServiceImpl();
    }

    @Test
    void acceptsGeneratedQuestionWithinProductionLimits() {
        Question question = validQuestion();
        question.setAnswer("算法说明\n\n```java\npublic class Main {}\n```");

        assertDoesNotThrow(() -> service.validQuestion(question, true));
    }

    @Test
    void createRequiresJudgeCasesAndResourceConfig() {
        Question question = validQuestion();
        question.setJudgeCase(null);

        assertThatThrownBy(() -> service.validQuestion(question, true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsMalformedOrIncompleteJudgeCases() {
        Question malformed = validQuestion();
        malformed.setJudgeCase("not-json");
        assertThatThrownBy(() -> service.validQuestion(malformed, true))
                .isInstanceOf(BusinessException.class);

        Question missingOutput = validQuestion();
        missingOutput.setJudgeCase("[{\"input\":\"1\"}]");
        assertThatThrownBy(() -> service.validQuestion(missingOutput, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("输入输出");
    }

    @Test
    void rejectsUnsafeResourceLimits() {
        Question question = validQuestion();
        question.setJudgeConfig("{\"timeLimit\":60000,\"memoryLimit\":262144,\"stackLimit\":65536}");

        assertThatThrownBy(() -> service.validQuestion(question, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("资源限制");
    }

    @Test
    void validatesAnswerLengthInsteadOfAccidentallyCheckingContentLength() {
        Question question = validQuestion();
        question.setAnswer(repeat('a', 200 * 1024 + 1));

        assertThatThrownBy(() -> service.validQuestion(question, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("答案过长");
    }

    private Question validQuestion() {
        Question question = new Question();
        question.setTitle("AI generated problem");
        question.setContent("## 题目描述\n内容");
        question.setTags("[\"数组\"]");
        question.setAnswer("算法说明");
        question.setJudgeCase("[{\"input\":\"1\\n\",\"output\":\"2\"}]");
        question.setJudgeConfig("{\"timeLimit\":1000,\"memoryLimit\":262144,\"stackLimit\":262144}");
        return question;
    }

    private String repeat(char value, int size) {
        StringBuilder builder = new StringBuilder(size);
        for (int index = 0; index < size; index++) {
            builder.append(value);
        }
        return builder.toString();
    }
}

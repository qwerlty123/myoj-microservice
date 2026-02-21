package com.qwerlty.myojbackendaiservice.tools;

import com.qwerlty.myojbackendaiservice.agent.GenerationSession;
import com.qwerlty.myojbackendaiservice.dto.JudgeCase;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TerminateToolsTest {

    @Test
    void storesAndEmitsAValidQuestion() {
        GenerationSession session = new GenerationSession(new SseEmitter());
        TerminateTools tools = new TerminateTools(session);
        List<JudgeCase> cases = List.of(
                new JudgeCase("1", "1"),
                new JudgeCase("2", "2"),
                new JudgeCase("3", "3"),
                new JudgeCase("4", "4"),
                new JudgeCase("5", "5"),
                new JudgeCase("6", "6")
        );
        session.markCodeTestPassed(cases);

        String response = tools.doTerminate("# 题目", "# 解法", cases);

        assertThat(response).isEqualTo("题目已提交，任务结束");
        assertThat(session.isTerminated()).isTrue();
        assertThat(session.getResult().judgeCaseList()).containsExactlyElementsOf(cases);
    }

    @Test
    void rejectsTooFewCasesWithoutTerminating() {
        GenerationSession session = new GenerationSession(new SseEmitter());
        TerminateTools tools = new TerminateTools(session);

        String response = tools.doTerminate("# 题目", "# 解法", List.of(new JudgeCase("1", "1")));

        assertThat(response).contains("6 至 8");
        assertThat(session.isTerminated()).isFalse();
    }

    @Test
    void rejectsQuestionWithoutSuccessfulCodeTest() {
        GenerationSession session = new GenerationSession(new SseEmitter());
        TerminateTools tools = new TerminateTools(session);
        List<JudgeCase> cases = validCases();

        String response = tools.doTerminate("# 题目", "# 解法", cases);

        assertThat(response).contains("尚未通过代码沙箱验证");
        assertThat(session.isTerminated()).isFalse();
    }

    @Test
    void rejectsCasesChangedAfterCodeTest() {
        GenerationSession session = new GenerationSession(new SseEmitter());
        TerminateTools tools = new TerminateTools(session);
        session.markCodeTestPassed(validCases());
        List<JudgeCase> changedCases = List.of(
                new JudgeCase("1", "错误输出"),
                new JudgeCase("2", "2"),
                new JudgeCase("3", "3"),
                new JudgeCase("4", "4"),
                new JudgeCase("5", "5"),
                new JudgeCase("6", "6")
        );

        String response = tools.doTerminate("# 题目", "# 解法", changedCases);

        assertThat(response).contains("尚未通过代码沙箱验证");
        assertThat(session.isTerminated()).isFalse();
    }

    private static List<JudgeCase> validCases() {
        return List.of(
                new JudgeCase("1", "1"),
                new JudgeCase("2", "2"),
                new JudgeCase("3", "3"),
                new JudgeCase("4", "4"),
                new JudgeCase("5", "5"),
                new JudgeCase("6", "6")
        );
    }
}

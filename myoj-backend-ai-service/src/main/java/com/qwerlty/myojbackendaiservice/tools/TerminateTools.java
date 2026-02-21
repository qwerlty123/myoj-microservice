package com.qwerlty.myojbackendaiservice.tools;

import com.qwerlty.myojbackendaiservice.agent.GenerationSession;
import com.qwerlty.myojbackendaiservice.dto.GeneratedQuestion;
import com.qwerlty.myojbackendaiservice.dto.JudgeCase;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public class TerminateTools {

    private final GenerationSession session;

    public TerminateTools(GenerationSession session) {
        this.session = session;
    }

    @Tool(name = "doTerminate", description = "Submit the final generated question and terminate the task after code testing succeeds")
    public String doTerminate(
            @ToolParam(description = "题目描述 Markdown") String questionDescription,
            @ToolParam(description = "Java 解法教学 Markdown") String answer,
            @ToolParam(description = "6 至 8 组已通过代码沙箱验证的测试用例") List<JudgeCase> judgeCaseList) {
        if (questionDescription == null || questionDescription.isBlank()) {
            return "提交失败：题目描述为空";
        }
        if (answer == null || answer.isBlank()) {
            return "提交失败：解法教学为空";
        }
        if (judgeCaseList == null || judgeCaseList.size() < 6 || judgeCaseList.size() > 8) {
            return "提交失败：测试用例必须为 6 至 8 组";
        }
        boolean invalidCase = judgeCaseList.stream().anyMatch(item -> item == null
                || item.input() == null || item.output() == null);
        if (invalidCase) {
            return "提交失败：测试用例的 input 和 output 不能为 null";
        }
        if (!session.hasPassedCodeTest(judgeCaseList)) {
            return "提交失败：当前测试用例尚未通过代码沙箱验证，请先调用 doCodeTest";
        }
        GeneratedQuestion question = new GeneratedQuestion(questionDescription, answer, judgeCaseList);
        if (!session.terminate(question)) {
            return "任务已经提交，无需重复调用 doTerminate";
        }
        return "题目已提交，任务结束";
    }
}

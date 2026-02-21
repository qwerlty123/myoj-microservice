package com.qwerlty.myojbackendaiservice.tools;

import com.qwerlty.myojbackendaiservice.agent.GenerationSession;
import com.qwerlty.myojbackendaiservice.dto.JudgeCase;
import com.qwerlty.myojbackendaiservice.enums.MessageType;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecuteResponse;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecutionProfile;
import com.qwerlty.myojbackendaiservice.sandbox.SignedCodeSandboxClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.util.List;

public class CodeAndCaseTestTools {

    private final SignedCodeSandboxClient sandboxClient;
    private final GenerationSession session;

    public CodeAndCaseTestTools(SignedCodeSandboxClient sandboxClient, GenerationSession session) {
        this.sandboxClient = sandboxClient;
        this.session = session;
    }

    @Tool(name = "doCodeTest", description = "Execute Java ACM code against all test cases. "
            + "The backend compares actual outputs with expected outputs and only records success when every case matches.")
    public String executeCode(
            @ToolParam(description = "Complete Java Main source code") String code,
            @ToolParam(description = "Programming language; only java is supported") String language,
            @ToolParam(description = "All test cases, each containing input and expected output") List<JudgeCase> judgeCaseList) {
        session.clearCodeTestVerification();
        if (judgeCaseList == null || judgeCaseList.isEmpty()) {
            return "测试失败：测试用例不能为空";
        }
        boolean invalidCase = judgeCaseList.stream().anyMatch(item -> item == null
                || item.input() == null || item.output() == null);
        if (invalidCase) {
            return "测试失败：测试用例的 input 和 output 不能为 null";
        }
        List<String> inputList = judgeCaseList.stream().map(JudgeCase::input).toList();
        try {
            session.emit(MessageType.TOOL, "正在使用代码沙箱验证 " + judgeCaseList.size() + " 组测试用例");
            SandboxExecuteResponse response = sandboxClient.execute(
                    code, language, inputList, SandboxExecutionProfile.aiValidation());
            if (!response.successful()) {
                return "测试失败：" + concise(response.message());
            }
            List<String> actualOutputs = response.outputList();
            if (actualOutputs == null || actualOutputs.size() != judgeCaseList.size()) {
                return "测试失败：沙箱返回结果数量与测试用例数量不一致";
            }
            for (int index = 0; index < judgeCaseList.size(); index++) {
                String expected = normalizeOutput(judgeCaseList.get(index).output());
                String actual = normalizeOutput(actualOutputs.get(index));
                if (!expected.equals(actual)) {
                    return "测试失败：第 " + (index + 1) + " 组输出不一致；预期输出："
                            + concise(expected) + "；实际输出：" + concise(actual);
                }
            }
            session.markCodeTestPassed(judgeCaseList);
            return "测试成功：全部 " + judgeCaseList.size() + " 组测试用例输出一致";
        } catch (Exception exception) {
            return "测试失败：" + concise(exception.getMessage());
        }
    }

    private static String normalizeOutput(String output) {
        if (output == null) {
            return "";
        }
        return output.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
    }

    private static String concise(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}

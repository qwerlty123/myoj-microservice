package com.qwerlty.myojbackendaiservice.authoring.graph;

import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringProblemDraft;
import com.qwerlty.myojbackendaiservice.dto.JudgeCase;
import com.qwerlty.myojbackendaiservice.observability.AiMetrics;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecuteResponse;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecutionProfile;
import com.qwerlty.myojbackendaiservice.sandbox.SignedCodeSandboxClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AuthoringSandboxVerifier {

    private final SignedCodeSandboxClient sandboxClient;
    private final AiMetrics metrics;

    public AuthoringSandboxVerifier(SignedCodeSandboxClient sandboxClient, AiMetrics metrics) {
        this.sandboxClient = sandboxClient;
        this.metrics = metrics;
    }

    public SandboxVerification verify(AuthoringProblemDraft draft) {
        try {
            List<String> inputs = draft.judgeCase().stream().map(JudgeCase::input).toList();
            SandboxExecuteResponse response = sandboxClient.execute(
                    draft.referenceCode(), "java", inputs, SandboxExecutionProfile.aiValidation());
            if (!response.successful()) {
                metrics.sandbox("execution_failed");
                return new SandboxVerification(false,
                        List.of("代码沙箱执行失败：" + concise(response.message())));
            }
            if (response.outputList() == null || response.outputList().size() != draft.judgeCase().size()) {
                metrics.sandbox("invalid_response");
                return new SandboxVerification(false, List.of("沙箱返回结果数量与测试用例数量不一致"));
            }
            List<String> errors = new ArrayList<>();
            for (int index = 0; index < draft.judgeCase().size(); index++) {
                String expected = AuthoringDraftValidator.normalize(draft.judgeCase().get(index).output());
                String actual = AuthoringDraftValidator.normalize(response.outputList().get(index));
                if (!expected.equals(actual)) {
                    errors.add("第 " + (index + 1) + " 组输出不一致；预期："
                            + concise(expected) + "；实际：" + concise(actual));
                }
            }
            metrics.sandbox(errors.isEmpty() ? "passed" : "output_mismatch");
            return new SandboxVerification(errors.isEmpty(), List.copyOf(errors));
        } catch (RuntimeException exception) {
            metrics.sandbox("error");
            return new SandboxVerification(false,
                    List.of("代码沙箱调用异常：" + concise(exception.getMessage())));
        }
    }

    private static String concise(String value) {
        if (value == null || value.isBlank()) return "未知错误";
        return value.length() <= 300 ? value : value.substring(0, 300);
    }

    public record SandboxVerification(boolean passed, List<String> errors) {
    }
}

package com.qwerlty.myojbackendaiservice.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.agent.GenerationSession;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.dto.JudgeCase;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecuteResponse;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecutionProfile;
import com.qwerlty.myojbackendaiservice.sandbox.SignedCodeSandboxClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeAndCaseTestToolsTest {

    @Test
    void recordsVerificationOnlyWhenEveryOutputMatches() {
        SignedCodeSandboxClient sandboxClient = new FakeSandboxClient(
                successfulResponse(List.of("2\n", "4")));
        GenerationSession session = new GenerationSession(new SseEmitter());
        CodeAndCaseTestTools tools = new CodeAndCaseTestTools(sandboxClient, session);
        List<JudgeCase> cases = List.of(
                new JudgeCase("1", "2"),
                new JudgeCase("2", "4")
        );
        String response = tools.executeCode("class Main {}", "java", cases);

        assertThat(response).contains("测试成功");
        assertThat(session.hasPassedCodeTest(cases)).isTrue();
    }

    @Test
    void rejectsMismatchedOutputWithoutRecordingVerification() {
        SignedCodeSandboxClient sandboxClient = new FakeSandboxClient(
                successfulResponse(List.of("3")));
        GenerationSession session = new GenerationSession(new SseEmitter());
        CodeAndCaseTestTools tools = new CodeAndCaseTestTools(sandboxClient, session);
        List<JudgeCase> cases = List.of(new JudgeCase("1", "2"));

        String response = tools.executeCode("class Main {}", "java", cases);

        assertThat(response).contains("第 1 组输出不一致");
        assertThat(session.hasPassedCodeTest(cases)).isFalse();
    }

    @Test
    void failedRetestClearsPreviousVerification() {
        SignedCodeSandboxClient sandboxClient = new FakeSandboxClient(
                successfulResponse(List.of("3")));
        GenerationSession session = new GenerationSession(new SseEmitter());
        CodeAndCaseTestTools tools = new CodeAndCaseTestTools(sandboxClient, session);
        List<JudgeCase> cases = List.of(new JudgeCase("1", "2"));
        session.markCodeTestPassed(cases);

        tools.executeCode("class Main {}", "java", cases);

        assertThat(session.hasPassedCodeTest(cases)).isFalse();
    }

    private static SandboxExecuteResponse successfulResponse(List<String> outputs) {
        return new SandboxExecuteResponse(outputs, "ok", 1, null, null);
    }

    private static final class FakeSandboxClient extends SignedCodeSandboxClient {

        private final SandboxExecuteResponse response;

        private FakeSandboxClient(SandboxExecuteResponse response) {
            super(new AiAgentProperties(), new ObjectMapper());
            this.response = response;
        }

        @Override
        public SandboxExecuteResponse execute(String code, String language, List<String> inputList,
                                              SandboxExecutionProfile executionProfile) {
            return response;
        }
    }
}

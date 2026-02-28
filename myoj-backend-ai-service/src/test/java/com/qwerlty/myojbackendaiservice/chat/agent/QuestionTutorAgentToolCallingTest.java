package com.qwerlty.myojbackendaiservice.chat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatSendRequest;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatSession;
import com.qwerlty.myojbackendaiservice.chat.model.AiToolEvent;
import com.qwerlty.myojbackendaiservice.chat.model.QuestionContext;
import com.qwerlty.myojbackendaiservice.chat.repository.AiChatRepository;
import com.qwerlty.myojbackendaiservice.chat.tools.TutorToolContext;
import com.qwerlty.myojbackendaiservice.chat.tools.TutorToolResult;
import com.qwerlty.myojbackendaiservice.chat.tools.TutorToolService;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.observability.AiMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionTutorAgentToolCallingTest {

    @Test
    void executesNativeToolCallAndReturnsToolResponseToModel() {
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                int call = modelCalls.incrementAndGet();
                OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
                assertThat(options.getInternalToolExecutionEnabled()).isFalse();
                assertThat(options.getToolCallbacks()).extracting(callback -> callback.getToolDefinition().name())
                        .containsExactlyInAnyOrder(
                                "searchWeb",
                                "submission_analysis",
                                "testcase_generator",
                                "sample_error_analyzer",
                                "run_user_code");
                if (call == 1) {
                    AssistantMessage toolCall = AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall(
                                    "call-1", "function", "testcase_generator", "{}")))
                            .build();
                    return new ChatResponse(List.of(new Generation(toolCall)));
                }
                assertThat(prompt.getInstructions()).anyMatch(ToolResponseMessage.class::isInstance);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("最终辅导回答"))));
            }
        };

        AiAgentProperties properties = new AiAgentProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        StubRepository repository = new StubRepository();
        StubToolService toolService = new StubToolService(repository, properties, objectMapper);
        QuestionTutorAgent agent = new QuestionTutorAgent(
                chatModel, repository, toolService, properties, objectMapper,
                new AiMetrics(new SimpleMeterRegistry()), "test-model");

        TutorAnswer answer = agent.answer(
                7L,
                new AiChatSession(11L, 7L, 1L, "agent", 1, null, null, null, null, null),
                new QuestionContext(1L, "测试题", "题目内容", "数组", null, null, 1),
                new AiChatSendRequest("tool-call-test", 1L, "agent", "帮我分析边界", "java",
                        null, null, List.of()),
                List.of(),
                null);

        assertThat(modelCalls).hasValue(2);
        assertThat(toolService.calls).hasValue(1);
        assertThat(answer.content()).isEqualTo("最终辅导回答");
        assertThat(answer.toolEvents()).extracting(AiToolEvent::toolName)
                .containsExactly("testcase_generator");
    }

    private static final class StubRepository extends AiChatRepository {

        private StubRepository() {
            super(null);
        }

        @Override
        public Optional<PromptDefinition> findActivePromptDefinition(String scene) {
            return Optional.empty();
        }

        @Override
        public Optional<String> findActiveModelName() {
            return Optional.empty();
        }
    }

    private static final class StubToolService extends TutorToolService {

        private final AtomicInteger calls = new AtomicInteger();

        private StubToolService(AiChatRepository repository,
                                AiAgentProperties properties,
                                ObjectMapper objectMapper) {
            super(repository, null, null, properties, objectMapper,
                    new AiMetrics(new SimpleMeterRegistry()));
        }

        @Override
        public TutorToolResult execute(String toolName, String rawInput, TutorToolContext context) {
            calls.incrementAndGet();
            return new TutorToolResult(
                    new AiToolEvent(toolName, "done", "已生成测试建议"),
                    "边界测试建议");
        }
    }
}

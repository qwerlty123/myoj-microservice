package com.qwerlty.myojbackendaiservice.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.config.ToolFactory;
import com.qwerlty.myojbackendaiservice.sandbox.SignedCodeSandboxClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CreateQuestionAgentTest {

    @Test
    void stopsAfterConfiguredMaximumWhenModelNeverTerminates() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                return new ChatResponse(List.of(
                        new Generation(new AssistantMessage("继续思考"))
                ));
            }
        };

        AiAgentProperties properties = new AiAgentProperties();
        properties.setMaxSteps(3);
        ObjectMapper objectMapper = new ObjectMapper();
        CreateQuestionAgent agent = new CreateQuestionAgent(
                chatModel,
                new ToolFactory(properties, objectMapper, new SignedCodeSandboxClient(properties, objectMapper)),
                properties
        );

        agent.run(new SseEmitter(), "中等", "数组与双指针");

        assertThat(calls).hasValue(3);
    }
}

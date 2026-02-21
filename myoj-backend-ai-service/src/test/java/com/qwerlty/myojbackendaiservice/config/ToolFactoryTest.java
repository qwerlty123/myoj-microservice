package com.qwerlty.myojbackendaiservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.agent.GenerationSession;
import com.qwerlty.myojbackendaiservice.sandbox.SignedCodeSandboxClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ToolFactoryTest {

    @Test
    void registersTheSameFourToolsAsTheReferenceAgent() {
        AiAgentProperties properties = new AiAgentProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        ToolFactory factory = new ToolFactory(properties, objectMapper,
                new SignedCodeSandboxClient(properties, objectMapper));
        Object[] toolObjects = factory.createQuestionTools(new GenerationSession(new SseEmitter()));

        Set<String> names = java.util.Arrays.stream(ToolCallbacks.from(toolObjects))
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder("doCodeTest", "crawler", "webSearch", "doTerminate");
    }
}

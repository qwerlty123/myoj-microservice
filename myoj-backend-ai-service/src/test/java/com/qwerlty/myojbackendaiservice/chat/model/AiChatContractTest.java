package com.qwerlty.myojbackendaiservice.chat.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void exposesReferenceCompatibleSessionAndMessageFields() throws Exception {
        AiChatMessageView message = new AiChatMessageView(
                "9007199254740993", "assistant", "agent", "answer", "answer", "answer",
                "[]", LocalDateTime.of(2026, 8, 19, 12, 0), 25L);
        AiChatSessionView session = new AiChatSessionView(
                "9007199254740992", 0, "agent", true, null, List.of(message));

        JsonNode json = objectMapper.valueToTree(session);

        assertThat(json.path("sessionId").asText()).isEqualTo("9007199254740992");
        assertThat(json.path("status").asInt()).isZero();
        assertThat(json.path("messageList").get(0).path("id").asText()).isEqualTo("9007199254740993");
        assertThat(json.path("messageList").get(0).has("toolCalls")).isTrue();
    }

    @Test
    void scopesRequestsOnlyByQuestion() {
        JsonNode sessionRequest = objectMapper.valueToTree(new AiChatSessionRequest(1L));
        JsonNode sendRequest = objectMapper.valueToTree(new AiChatSendRequest(
                1L, "normal", "请提示思路", "java", null, null, List.of()));

        assertThat(sessionRequest.has("contestId")).isFalse();
        assertThat(sendRequest.has("contestId")).isFalse();
        assertThat(sessionRequest.path("questionId").asLong()).isEqualTo(1L);
    }
}

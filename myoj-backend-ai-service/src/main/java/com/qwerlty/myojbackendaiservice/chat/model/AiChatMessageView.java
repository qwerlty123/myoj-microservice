package com.qwerlty.myojbackendaiservice.chat.model;

import java.time.LocalDateTime;
public record AiChatMessageView(
        String id,
        String role,
        String mode,
        String content,
        String rawContent,
        String finalContent,
        String toolCalls,
        LocalDateTime createTime,
        Long reasoningDurationMs,
        String traceId,
        String modelName,
        String promptVersion,
        Integer promptTokens,
        Integer completionTokens
) {

    public AiChatMessageView(String id, String role, String mode, String content,
                             String rawContent, String finalContent, String toolCalls,
                             LocalDateTime createTime, Long reasoningDurationMs) {
        this(id, role, mode, content, rawContent, finalContent, toolCalls, createTime,
                reasoningDurationMs, null, null, null, null, null);
    }
}

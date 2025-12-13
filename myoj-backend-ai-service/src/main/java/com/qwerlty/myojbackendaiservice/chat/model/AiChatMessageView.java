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
        Long reasoningDurationMs
) {
}

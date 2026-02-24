package com.qwerlty.myojbackendaiservice.chat.model;

import java.time.LocalDateTime;

public record AiChatMessage(
        long id,
        long sessionId,
        String role,
        String mode,
        String content,
        String toolEvents,
        boolean violation,
        String traceId,
        String modelName,
        String promptVersion,
        Long latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        LocalDateTime createTime
) {
}

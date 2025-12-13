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
        LocalDateTime createTime
) {
}

package com.qwerlty.myojbackendaiservice.chat.model;

import java.time.LocalDateTime;

public record AiChatSession(
        long id,
        long userId,
        long questionId,
        String mode,
        int status,
        String disableReason,
        LocalDateTime lastMessageTime,
        LocalDateTime expireTime,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}

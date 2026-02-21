package com.qwerlty.myojbackendaiservice.chat.model;

import java.util.List;

public record AiChatSessionView(
        String sessionId,
        int status,
        String mode,
        boolean enabled,
        String disableReason,
        List<AiChatMessageView> messageList
) {
}

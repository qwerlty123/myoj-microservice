package com.qwerlty.myojbackendaiservice.chat.tools;

import com.qwerlty.myojbackendaiservice.chat.model.AiChatSendRequest;
import com.qwerlty.myojbackendaiservice.chat.model.QuestionContext;

public record TutorToolContext(
        long userId,
        long sessionId,
        QuestionContext question,
        AiChatSendRequest request
) {
}

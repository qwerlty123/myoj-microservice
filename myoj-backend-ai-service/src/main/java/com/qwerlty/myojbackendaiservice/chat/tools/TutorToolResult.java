package com.qwerlty.myojbackendaiservice.chat.tools;

import com.qwerlty.myojbackendaiservice.chat.model.AiToolEvent;

public record TutorToolResult(AiToolEvent event, String output) {
}

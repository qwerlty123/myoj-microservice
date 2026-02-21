package com.qwerlty.myojbackendaiservice.chat.agent;

import com.qwerlty.myojbackendaiservice.chat.model.AiToolEvent;

import java.util.List;

public record TutorAnswer(String content, List<AiToolEvent> toolEvents) {
}

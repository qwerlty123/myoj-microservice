package com.qwerlty.myojbackendaiservice.chat.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record QuestionContext(
        Long id,
        String title,
        String content,
        String tags,
        String judgeCase,
        String judgeConfig,
        Integer difficulty
) {
}

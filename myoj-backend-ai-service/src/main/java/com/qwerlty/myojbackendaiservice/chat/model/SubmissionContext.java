package com.qwerlty.myojbackendaiservice.chat.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SubmissionContext(
        Long id,
        String language,
        String code,
        String judgeInfo,
        Integer status,
        Long questionId,
        Long userId,
        String createTime,
        String lastError
) {
}

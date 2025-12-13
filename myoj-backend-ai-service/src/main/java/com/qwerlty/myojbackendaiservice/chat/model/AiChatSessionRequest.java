package com.qwerlty.myojbackendaiservice.chat.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AiChatSessionRequest(
        @NotNull(message = "题目编号不能为空")
        @Positive(message = "题目编号必须大于 0")
        Long questionId
) {
}

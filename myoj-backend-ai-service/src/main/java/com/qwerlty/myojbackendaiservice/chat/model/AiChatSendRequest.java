package com.qwerlty.myojbackendaiservice.chat.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AiChatSendRequest(
        @NotBlank(message = "客户端消息编号不能为空")
        @Size(max = 64, message = "客户端消息编号不能超过 64 个字符")
        String clientMessageId,
        @NotNull(message = "题目编号不能为空")
        @Positive(message = "题目编号必须大于 0")
        Long questionId,
        String mode,
        @NotBlank(message = "消息不能为空")
        @Size(max = 4000, message = "消息不能超过 4000 个字符")
        String message,
        @Size(max = 32, message = "语言名称过长")
        String language,
        @Size(max = 40000, message = "代码不能超过 40000 个字符")
        String userCode,
        @Size(max = 4000, message = "判题结果不能超过 4000 个字符")
        String latestJudgeResult,
        List<String> testInputs
) {
    public ChatMode resolvedMode() {
        return ChatMode.from(mode);
    }
}

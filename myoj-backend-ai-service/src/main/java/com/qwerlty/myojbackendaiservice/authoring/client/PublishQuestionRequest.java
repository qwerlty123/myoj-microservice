package com.qwerlty.myojbackendaiservice.authoring.client;

import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringProblemDraft;
import com.qwerlty.myojbackendaiservice.dto.JudgeCase;

import java.util.List;

public record PublishQuestionRequest(
        String idempotencyKey,
        long sourceTaskId,
        long reviewerId,
        String payloadHash,
        String title,
        Integer difficulty,
        String content,
        List<String> tags,
        String answer,
        List<JudgeCase> judgeCase,
        AuthoringProblemDraft.JudgeConfig judgeConfig
) {
}

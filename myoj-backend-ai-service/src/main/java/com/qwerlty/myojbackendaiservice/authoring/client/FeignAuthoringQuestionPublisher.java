package com.qwerlty.myojbackendaiservice.authoring.client;

import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringProblemDraft;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class FeignAuthoringQuestionPublisher implements AuthoringQuestionPublisher {

    private final QuestionPublishingClient client;

    public FeignAuthoringQuestionPublisher(QuestionPublishingClient client) {
        this.client = client;
    }

    @Override
    public long publish(long taskId, long reviewerId, String draftJson, AuthoringProblemDraft draft) {
        PublishQuestionRequest request = new PublishQuestionRequest(
                "ai-authoring-task-" + taskId + "-publish-v1",
                taskId,
                reviewerId,
                sha256(draftJson),
                draft.title(),
                draft.difficulty(),
                draft.content(),
                draft.tags(),
                draft.answer(),
                draft.judgeCase(),
                draft.judgeConfig()
        );
        Long questionId = client.publish(request);
        if (questionId == null || questionId <= 0) {
            throw new IllegalStateException("题目服务未返回有效的发布题目 ID");
        }
        return questionId;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算发布内容摘要", exception);
        }
    }
}

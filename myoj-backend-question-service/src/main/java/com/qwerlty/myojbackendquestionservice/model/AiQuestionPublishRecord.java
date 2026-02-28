package com.qwerlty.myojbackendquestionservice.model;

import lombok.Data;

@Data
public class AiQuestionPublishRecord {

    private String idempotencyKey;
    private Long sourceTaskId;
    private Long reviewerId;
    private String payloadHash;
    private Long questionId;
}

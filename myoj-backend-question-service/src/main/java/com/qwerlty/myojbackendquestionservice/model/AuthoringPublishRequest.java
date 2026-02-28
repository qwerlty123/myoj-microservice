package com.qwerlty.myojbackendquestionservice.model;

import com.qwerlty.myojbackendmodel.model.dto.question.JudgeCase;
import com.qwerlty.myojbackendmodel.model.dto.question.JudgeConfig;
import lombok.Data;

import java.util.List;

@Data
public class AuthoringPublishRequest {

    private String idempotencyKey;
    private Long sourceTaskId;
    private Long reviewerId;
    private String payloadHash;
    private String title;
    private Integer difficulty;
    private String content;
    private List<String> tags;
    private String answer;
    private List<JudgeCase> judgeCase;
    private JudgeConfig judgeConfig;
}

package com.qwerlty.myojbackendquestionservice.model.dto;

import com.qwerlty.myojbackendmodel.model.dto.question.QuestionAddRequest;
import lombok.Data;

import java.io.Serializable;

@Data
public class AiQuestionSubmissionRequest implements Serializable {
    private Long parentSubmissionId;
    private Long testCasesTaskId;
    private QuestionAddRequest snapshot;
}

package com.qwerlty.myojbackendquestionservice.model.dto;

import com.qwerlty.myojbackendmodel.model.dto.question.QuestionAddRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiArtifactValidationRequest {
    private Long userId;
    private Long testCasesTaskId;
    private QuestionAddRequest snapshot;
}

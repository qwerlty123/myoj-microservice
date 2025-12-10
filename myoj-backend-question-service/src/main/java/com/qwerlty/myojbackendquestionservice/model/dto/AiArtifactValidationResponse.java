package com.qwerlty.myojbackendquestionservice.model.dto;

import lombok.Data;

@Data
public class AiArtifactValidationResponse {
    private Boolean valid;
    private Long sourceTaskId;
    private String executionHash;
}

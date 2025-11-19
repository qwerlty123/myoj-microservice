package com.qwerlty.myojbackendaiservice.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class AiFeedbackAddRequest {
    @NotNull
    @Positive
    private Long submissionId;
}

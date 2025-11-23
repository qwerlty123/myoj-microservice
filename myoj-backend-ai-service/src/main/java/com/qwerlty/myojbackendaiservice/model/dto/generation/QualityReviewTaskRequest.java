package com.qwerlty.myojbackendaiservice.model.dto.generation;

import com.qwerlty.myojbackendaiservice.generation.workflow.AuthoringRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class QualityReviewTaskRequest implements AuthoringRequest {
    @Valid @NotNull
    private ProblemSourceDraft sourceDraft;
}

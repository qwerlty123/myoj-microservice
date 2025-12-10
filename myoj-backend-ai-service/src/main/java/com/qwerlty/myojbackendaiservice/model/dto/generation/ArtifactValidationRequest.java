package com.qwerlty.myojbackendaiservice.model.dto.generation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ArtifactValidationRequest {
    @NotNull
    private Long userId;
    @NotNull
    private Long testCasesTaskId;
    @Valid @NotNull
    private ProblemSourceDraft snapshot;
}

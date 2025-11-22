package com.qwerlty.myojbackendaiservice.model.dto.generation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GenerationTaskCreateRequest {
    @NotBlank
    private String mode;

    @Valid
    @NotNull
    private GenerationRequirements requirements;

    @Valid
    private ProblemSourceDraft sourceDraft;
}

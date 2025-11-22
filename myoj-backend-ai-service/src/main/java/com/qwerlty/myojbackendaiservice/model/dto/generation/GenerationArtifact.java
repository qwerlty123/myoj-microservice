package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GenerationArtifact {
    private GeneratedProblemDraft draft;
    private GenerationValidationReport validation;
}

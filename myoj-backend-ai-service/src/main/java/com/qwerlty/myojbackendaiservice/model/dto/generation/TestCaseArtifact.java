package com.qwerlty.myojbackendaiservice.model.dto.generation;

import com.qwerlty.myojbackendaiservice.generation.workflow.AuthoringArtifact;
import com.qwerlty.myojbackendaiservice.generation.workflow.ToolCallTrace;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.List;

@Data @NoArgsConstructor @AllArgsConstructor
public class TestCaseArtifact implements AuthoringArtifact {
    private String sourceHash;
    private List<GeneratedJudgeCase> judgeCases = new ArrayList<>();
    private CoverageReport coverage;
    private GenerationValidationReport validation;
    private List<ToolCallTrace> toolTrace = new ArrayList<>();
    private boolean migratedLegacy;
}

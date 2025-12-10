package com.qwerlty.myojbackendaiservice.model.dto.generation;

import com.qwerlty.myojbackendaiservice.generation.workflow.AuthoringRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TestCaseTaskRequest implements AuthoringRequest {
    @NotNull
    private Long sourceTaskId;
    @Valid @NotNull
    private ProblemSourceDraft sourceDraft;
    @Min(10) @Max(50) @NotNull
    private Integer caseCount = 20;
    @Size(max = 4000)
    private String constraints;
}

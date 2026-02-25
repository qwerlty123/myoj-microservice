package com.qwerlty.myojbackendaiservice.authoring.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CreateAuthoringTaskRequest(
        @Valid @NotNull(message = "出题需求不能为空") ProblemDraftRequirements requirements
) {
}

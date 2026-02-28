package com.qwerlty.myojbackendaiservice.authoring.api;

import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringProblemDraft;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringReviewDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewAuthoringTaskRequest(
        @NotNull AuthoringReviewDecision decision,
        AuthoringProblemDraft draft,
        @Size(max = 1000) String comment
) {
}

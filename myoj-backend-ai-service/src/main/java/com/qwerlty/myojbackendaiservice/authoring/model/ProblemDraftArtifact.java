package com.qwerlty.myojbackendaiservice.authoring.model;

public record ProblemDraftArtifact(
        AuthoringProblemDraft draft,
        AuthoringValidation validation
) {
}

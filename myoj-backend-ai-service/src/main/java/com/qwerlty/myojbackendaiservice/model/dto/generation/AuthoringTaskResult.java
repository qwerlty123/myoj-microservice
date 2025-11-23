package com.qwerlty.myojbackendaiservice.model.dto.generation;

import com.qwerlty.myojbackendaiservice.generation.workflow.AuthoringArtifact;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;

public record AuthoringTaskResult(AuthoringTaskType type, int schemaVersion, AuthoringArtifact data) {
    public static AuthoringTaskResult of(AuthoringTaskType type, AuthoringArtifact data) {
        return new AuthoringTaskResult(type, 1, data);
    }
}

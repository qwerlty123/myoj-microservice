package com.qwerlty.myojbackendaiservice.authoring.model;

public enum AuthoringStage {
    QUEUED,
    GENERATING_DRAFT,
    VALIDATING_DRAFT,
    VERIFYING_IN_SANDBOX,
    REPAIRING_DRAFT,
    COMPLETED,
    FAILED,
    CANCELLED
}

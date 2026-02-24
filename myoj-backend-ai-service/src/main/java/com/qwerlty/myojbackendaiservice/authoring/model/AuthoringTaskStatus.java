package com.qwerlty.myojbackendaiservice.authoring.model;

public enum AuthoringTaskStatus {
    PENDING,
    RUNNING,
    REVIEW_REQUIRED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == REVIEW_REQUIRED || this == FAILED || this == CANCELLED;
    }
}

package com.qwerlty.myojbackendaiservice.authoring.model;

public enum AuthoringTaskStatus {
    PENDING,
    RUNNING,
    REVIEW_REQUIRED,
    PUBLISHED,
    REJECTED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == REVIEW_REQUIRED || this == PUBLISHED || this == REJECTED
                || this == FAILED || this == CANCELLED;
    }
}

package com.qwerlty.myojbackendaiservice.model.enums;

public enum GenerationLane {
    PUBLIC_AUTHORING,
    ADMIN_REVIEW;

    public static GenerationLane forType(AuthoringTaskType type) {
        return type == AuthoringTaskType.QUALITY_REVIEW ? ADMIN_REVIEW : PUBLIC_AUTHORING;
    }
}

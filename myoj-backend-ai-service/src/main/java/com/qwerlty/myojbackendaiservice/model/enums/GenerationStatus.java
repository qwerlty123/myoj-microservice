package com.qwerlty.myojbackendaiservice.model.enums;

import lombok.Getter;

@Getter
public enum GenerationStatus {
    PENDING(0),
    RUNNING(1),
    REVIEW_REQUIRED(2),
    FAILED(3),
    TIMED_OUT(4),
    CANCELLED(5);

    private final int value;

    GenerationStatus(int value) {
        this.value = value;
    }

    public static GenerationStatus fromValue(Integer value) {
        if (value == null) {
            return null;
        }
        for (GenerationStatus status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        return null;
    }
}

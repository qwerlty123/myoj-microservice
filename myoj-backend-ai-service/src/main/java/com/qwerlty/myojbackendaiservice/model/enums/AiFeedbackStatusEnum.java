package com.qwerlty.myojbackendaiservice.model.enums;

import lombok.Getter;

@Getter
public enum AiFeedbackStatusEnum {
    PENDING(0),
    DISPATCHING(1),
    QUEUED(2),
    RUNNING(3),
    SUCCESS(4),
    FAILED(5),
    TIMEOUT(6);

    private final int value;

    AiFeedbackStatusEnum(int value) {
        this.value = value;
    }

    public static AiFeedbackStatusEnum fromValue(Integer value) {
        if (value != null) {
            for (AiFeedbackStatusEnum status : values()) {
                if (status.value == value) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("Unknown AI feedback status: " + value);
    }
}

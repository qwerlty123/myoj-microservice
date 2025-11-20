package com.qwerlty.myojbackendaiservice.model.enums;

import lombok.Getter;

@Getter
public enum AiFeedbackStatusEnum {
    PENDING(0),
    RUNNING(1),
    SUCCESS(2),
    FAILED(3),
    TIMEOUT(4);

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

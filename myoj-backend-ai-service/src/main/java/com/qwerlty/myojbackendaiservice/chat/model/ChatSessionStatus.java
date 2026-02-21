package com.qwerlty.myojbackendaiservice.chat.model;

public enum ChatSessionStatus {
    ACTIVE(0),
    ARCHIVED(1),
    DISABLED(2);

    private final int value;

    ChatSessionStatus(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}

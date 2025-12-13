package com.qwerlty.myojbackendaiservice.chat.model;

public enum ChatMode {
    NORMAL("normal"),
    AGENT("agent");

    private final String value;

    ChatMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ChatMode from(String value) {
        if (value != null && AGENT.value.equalsIgnoreCase(value.trim())) {
            return AGENT;
        }
        return NORMAL;
    }
}

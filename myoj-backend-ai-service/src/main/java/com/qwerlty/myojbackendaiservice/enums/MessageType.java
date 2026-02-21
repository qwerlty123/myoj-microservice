package com.qwerlty.myojbackendaiservice.enums;

public enum MessageType {
    TOOL("tool"),
    RESULT("result"),
    ERROR("error"),
    DONE("done");

    private final String eventName;

    MessageType(String eventName) {
        this.eventName = eventName;
    }

    public String getEventName() {
        return eventName;
    }
}

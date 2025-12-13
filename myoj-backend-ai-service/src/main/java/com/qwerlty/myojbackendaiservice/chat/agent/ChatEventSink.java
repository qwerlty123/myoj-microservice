package com.qwerlty.myojbackendaiservice.chat.agent;

@FunctionalInterface
public interface ChatEventSink {
    void emit(String eventName, Object data);
}

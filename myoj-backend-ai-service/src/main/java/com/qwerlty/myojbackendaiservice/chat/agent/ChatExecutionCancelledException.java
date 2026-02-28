package com.qwerlty.myojbackendaiservice.chat.agent;

public class ChatExecutionCancelledException extends RuntimeException {

    public ChatExecutionCancelledException() {
        super("AI 回复已取消");
    }

    public ChatExecutionCancelledException(Throwable cause) {
        super("AI 回复已取消", cause);
    }
}

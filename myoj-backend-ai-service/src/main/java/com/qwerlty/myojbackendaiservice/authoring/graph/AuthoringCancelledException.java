package com.qwerlty.myojbackendaiservice.authoring.graph;

public class AuthoringCancelledException extends RuntimeException {
    public AuthoringCancelledException() {
        super("AI 出题任务已取消");
    }
}

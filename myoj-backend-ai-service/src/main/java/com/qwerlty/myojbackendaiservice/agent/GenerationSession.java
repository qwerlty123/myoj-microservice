package com.qwerlty.myojbackendaiservice.agent;

import com.qwerlty.myojbackendaiservice.dto.GeneratedQuestion;
import com.qwerlty.myojbackendaiservice.dto.JudgeCase;
import com.qwerlty.myojbackendaiservice.enums.MessageType;
import com.qwerlty.myojbackendaiservice.utils.SseEmitterUtil;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GenerationSession {

    private final SseEmitter emitter;
    private final AtomicBoolean terminated = new AtomicBoolean();
    private volatile GeneratedQuestion result;
    private volatile List<JudgeCase> verifiedCases = List.of();

    public GenerationSession(SseEmitter emitter) {
        this.emitter = emitter;
    }

    public boolean emit(MessageType type, Object data) {
        return SseEmitterUtil.send(emitter, type, data);
    }

    public boolean terminate(GeneratedQuestion question) {
        if (!terminated.compareAndSet(false, true)) {
            return false;
        }
        result = question;
        emit(MessageType.RESULT, question);
        return true;
    }

    public boolean isTerminated() {
        return terminated.get();
    }

    public void clearCodeTestVerification() {
        verifiedCases = List.of();
    }

    public void markCodeTestPassed(List<JudgeCase> judgeCaseList) {
        verifiedCases = List.copyOf(judgeCaseList);
    }

    public boolean hasPassedCodeTest(List<JudgeCase> judgeCaseList) {
        return judgeCaseList != null && verifiedCases.equals(judgeCaseList);
    }

    public GeneratedQuestion getResult() {
        return result;
    }
}

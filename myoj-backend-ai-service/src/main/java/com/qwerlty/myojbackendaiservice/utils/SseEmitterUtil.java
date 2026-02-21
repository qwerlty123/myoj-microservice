package com.qwerlty.myojbackendaiservice.utils;

import com.qwerlty.myojbackendaiservice.enums.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public final class SseEmitterUtil {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterUtil.class);

    public static boolean send(SseEmitter emitter, MessageType type, Object data) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(type.getEventName()).data(data));
            }
            return true;
        } catch (Exception exception) {
            log.debug("SSE connection is no longer writable: {}", exception.getMessage());
            return false;
        }
    }

    private SseEmitterUtil() {
    }
}

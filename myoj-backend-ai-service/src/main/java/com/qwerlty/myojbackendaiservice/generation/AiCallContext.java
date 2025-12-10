package com.qwerlty.myojbackendaiservice.generation;

import com.qwerlty.myojbackendaiservice.model.enums.GenerationLane;

public final class AiCallContext {
    private static final ThreadLocal<Value> CURRENT = new ThreadLocal<>();

    private AiCallContext() { }

    public static void bind(Long taskId, GenerationLane lane, long deadlineEpochMs) {
        CURRENT.set(new Value(taskId, lane, deadlineEpochMs));
    }

    public static Value current() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }

    public record Value(Long taskId, GenerationLane lane, long deadlineEpochMs) { }
}

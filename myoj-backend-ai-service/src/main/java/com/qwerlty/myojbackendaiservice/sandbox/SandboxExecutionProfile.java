package com.qwerlty.myojbackendaiservice.sandbox;

public record SandboxExecutionProfile(
        String purpose,
        Long timeLimitMs,
        Long memoryLimitKb,
        Long stackLimitKb,
        Integer outputLimitBytes
) {
    public static SandboxExecutionProfile aiValidation() {
        return new SandboxExecutionProfile("AI_VALIDATION", 5_000L, 262_144L, 131_072L, 1_048_576);
    }

    public static SandboxExecutionProfile aiTutor() {
        return new SandboxExecutionProfile("AI_TUTOR", 5_000L, 262_144L, 131_072L, 1_048_576);
    }
}

package com.qwerlty.myojbackendaiservice.generation.workflow;

public record ToolCallTrace(
        int round,
        String toolName,
        int submitted,
        int accepted,
        int rejected,
        long latencyMs,
        String outcome,
        String errorType) {

    public ToolCallTrace(int round, String toolName, int submitted, int accepted,
                         int rejected, long latencyMs, String outcome) {
        this(round, toolName, submitted, accepted, rejected, latencyMs, outcome, null);
    }
}

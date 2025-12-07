package com.qwerlty.myojbackendaiservice.generation.workflow;

public record ToolCallTrace(
        int round,
        String toolName,
        int submitted,
        int accepted,
        int rejected,
        long latencyMs,
        String outcome,
        String errorType,
        String errorSummary) {

    public ToolCallTrace(int round, String toolName, int submitted, int accepted,
                         int rejected, long latencyMs, String outcome) {
        this(round, toolName, submitted, accepted, rejected, latencyMs, outcome, null, null);
    }

    public ToolCallTrace(int round, String toolName, int submitted, int accepted,
                         int rejected, long latencyMs, String outcome, String errorType) {
        this(round, toolName, submitted, accepted, rejected, latencyMs, outcome, errorType, null);
    }
}

package com.qwerlty.myojbackendaiservice.generation.workflow;

public record ToolCallTrace(
        int round,
        String toolName,
        int submitted,
        int accepted,
        int rejected,
        long latencyMs,
        String outcome) {
}

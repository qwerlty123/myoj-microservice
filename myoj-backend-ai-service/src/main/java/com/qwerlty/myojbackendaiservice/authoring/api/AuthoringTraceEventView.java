package com.qwerlty.myojbackendaiservice.authoring.api;

import java.time.LocalDateTime;

public record AuthoringTraceEventView(
        String eventId,
        String traceId,
        String runId,
        String graphThreadId,
        String graphVersion,
        String eventType,
        String nodeId,
        String fromNode,
        String toNode,
        String outcome,
        Long durationMs,
        String actorId,
        String detailJson,
        LocalDateTime createTime
) {
}

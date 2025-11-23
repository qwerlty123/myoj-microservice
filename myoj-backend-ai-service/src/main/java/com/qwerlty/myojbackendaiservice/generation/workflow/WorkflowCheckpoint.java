package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record WorkflowCheckpoint(
        int schemaVersion,
        String promptVersion,
        String completedStage,
        JsonNode data,
        List<ToolCallTrace> toolTrace) {
}

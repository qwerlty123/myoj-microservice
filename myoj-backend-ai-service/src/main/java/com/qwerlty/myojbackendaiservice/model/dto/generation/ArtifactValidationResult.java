package com.qwerlty.myojbackendaiservice.model.dto.generation;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record ArtifactValidationResult(
        boolean valid,
        @JsonSerialize(using = ToStringSerializer.class) Long sourceTaskId,
        String executionHash) { }

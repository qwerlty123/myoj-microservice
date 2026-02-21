package com.qwerlty.myojbackendaiservice.sandbox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxCaseResult(
        Integer index,
        Integer exitCode,
        String output,
        String error,
        Long timeMs,
        Boolean timedOut,
        Boolean outputLimitExceeded
) {
}

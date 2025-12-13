package com.qwerlty.myojbackendaiservice.sandbox;

import java.util.List;

public record SandboxExecuteRequest(
        List<String> inputList,
        String code,
        String language,
        SandboxExecutionProfile executionProfile
) {
}

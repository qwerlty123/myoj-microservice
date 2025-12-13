package com.qwerlty.myojbackendaiservice.sandbox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxExecuteResponse(
        List<String> outputList,
        String message,
        Integer status,
        SandboxJudgeInfo judgeInfo,
        List<SandboxCaseResult> caseResults
) {
    public boolean successful() {
        return Integer.valueOf(1).equals(status);
    }
}

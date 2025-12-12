package com.qwerlty.myojbackendaiservice.sandbox;

import lombok.Data;

@Data
public class SandboxCaseExecutionResult {
    private Integer index;
    private Integer exitCode;
    private String output;
    private String error;
    private Long timeMs;
    private Boolean timedOut;
    private Boolean outputLimitExceeded;
}

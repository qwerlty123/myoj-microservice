package com.qwerlty.myojbackendaiservice.sandbox;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SandboxExecuteResponse {
    private List<String> outputList = new ArrayList<>();
    private String message;
    private Integer status;
    private SandboxJudgeInfo judgeInfo;
    private List<SandboxCaseExecutionResult> caseResults = new ArrayList<>();
}

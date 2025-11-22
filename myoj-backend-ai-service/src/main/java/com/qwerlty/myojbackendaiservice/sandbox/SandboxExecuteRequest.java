package com.qwerlty.myojbackendaiservice.sandbox;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class SandboxExecuteRequest {
    private List<String> inputList;
    private String code;
    private String language;
    private SandboxExecutionProfile executionProfile;
}

package com.qwerlty.myojbackendaiservice.sandbox;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SandboxExecutionProfile {
    private String purpose;
    private Long timeLimitMs;
    private Long memoryLimitKb;
    private Long stackLimitKb;
    private Integer outputLimitBytes;
}

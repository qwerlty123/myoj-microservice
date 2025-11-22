package com.qwerlty.myojbackendaiservice.sandbox;

import java.util.List;

public interface CodeSandboxClient {
    SandboxExecuteResponse execute(String language,
                                   String code,
                                   List<String> inputs,
                                   long timeLimitMs,
                                   long memoryLimitKb,
                                   long stackLimitKb);
}

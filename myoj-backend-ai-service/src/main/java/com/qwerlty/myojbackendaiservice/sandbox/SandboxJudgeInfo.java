package com.qwerlty.myojbackendaiservice.sandbox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxJudgeInfo(String message, Long memory, Long time) {
}

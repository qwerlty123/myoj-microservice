package com.qwerlty.myojbackendaiservice.model.dto.generation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class JudgeConfigValue {
    @Min(100)
    @Max(15000)
    private Long timeLimit = 1000L;

    @Min(16384)
    @Max(524288)
    private Long memoryLimit = 262144L;

    @Min(256)
    @Max(262144)
    private Long stackLimit = 65536L;
}

package com.qwerlty.myojbackendaiservice.model.enums;

import lombok.Getter;

@Getter
public enum GenerationStage {
    QUEUED(0),
    GENERATING_SPEC(10),
    GENERATING_SOLUTIONS(30),
    GENERATING_CASES(50),
    COMPILING(65),
    CROSS_VALIDATING(80),
    QUALITY_CHECKING(95),
    COMPLETED(100);

    private final int progress;

    GenerationStage(int progress) {
        this.progress = progress;
    }
}

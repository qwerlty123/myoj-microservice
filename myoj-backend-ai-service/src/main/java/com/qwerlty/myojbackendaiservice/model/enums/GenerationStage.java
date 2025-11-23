package com.qwerlty.myojbackendaiservice.model.enums;

import lombok.Getter;

@Getter
public enum GenerationStage {
    QUEUED(0),
    DRAFTING_SPEC(10),
    GENERATING_REFERENCE_SOLUTIONS(35),
    VERIFYING_SAMPLES(75),
    ANALYZING_SOURCE(10),
    PLANNING_COVERAGE(20),
    AGENT_GENERATING_CASES(50),
    FINAL_VALIDATION(90),
    STATIC_CHECKING(15),
    SEMANTIC_REVIEWING(35),
    VERIFYING_EXISTING_CASES(60),
    AGENT_EVIDENCE_REVIEW(80),
    BUILDING_REPORT(95),
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

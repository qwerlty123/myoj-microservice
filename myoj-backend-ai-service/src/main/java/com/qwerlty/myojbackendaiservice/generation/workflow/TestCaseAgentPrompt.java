package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.model.dto.generation.CoveragePlan;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;

public record TestCaseAgentPrompt(
        GeneratedProblemSpec specification,
        CoveragePlan coveragePlan,
        int targetCount,
        String constraints) {
}

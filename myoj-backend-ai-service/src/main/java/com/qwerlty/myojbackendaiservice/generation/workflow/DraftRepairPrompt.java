package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationReport;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;

import java.util.List;

public record DraftRepairPrompt(
        GeneratedProblemSpec specification,
        List<ReferenceSolution> solutions,
        ValidationPrograms programs,
        VerificationReport verification,
        String stateHash,
        int remainingCalls) {
}

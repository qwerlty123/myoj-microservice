package com.qwerlty.myojbackendaiservice.model.dto.generation;

import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationIssue;

import java.util.List;

public record DraftPatchResult(
        String status,
        String stateHash,
        String summary,
        int remainingCalls,
        List<VerificationIssue> issues) {
}

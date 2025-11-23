package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateRejection;

import java.util.List;

public record BatchVerificationResult(
        List<VerifiedCandidate> accepted,
        List<CandidateRejection> rejected,
        int oracleCases) {
}

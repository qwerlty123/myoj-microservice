package com.qwerlty.myojbackendaiservice.generation.sandbox;

import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CaseEvidence;

public record VerifiedCandidate(CandidateTestInput candidate, String output, CaseEvidence evidence) {
}

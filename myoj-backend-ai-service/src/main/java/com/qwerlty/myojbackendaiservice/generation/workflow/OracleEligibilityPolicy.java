package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateTestInput;

import java.nio.charset.StandardCharsets;

final class OracleEligibilityPolicy {
    private static final int MAX_ORACLE_INPUT_BYTES = 8 * 1024;

    private OracleEligibilityPolicy() {
    }

    static void enforce(CandidateTestInput candidate) {
        int inputBytes = candidate.getInput() == null ? 0
                : candidate.getInput().getBytes(StandardCharsets.UTF_8).length;
        candidate.setOracleEligible(!"MAXIMUM".equals(candidate.getCategory())
                && inputBytes <= MAX_ORACLE_INPUT_BYTES);
    }
}

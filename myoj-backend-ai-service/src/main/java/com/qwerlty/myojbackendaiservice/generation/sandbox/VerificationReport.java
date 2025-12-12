package com.qwerlty.myojbackendaiservice.generation.sandbox;

import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateRejection;

import java.util.List;
import java.util.stream.Collectors;

public record VerificationReport(
        VerificationOutcome outcome,
        List<VerifiedCandidate> accepted,
        List<CandidateRejection> rejected,
        int oracleCases,
        List<VerificationIssue> issues) {

    public boolean passed() {
        return outcome == VerificationOutcome.PASSED;
    }

    public boolean hasArtifactIssue() {
        return issues.stream().anyMatch(issue -> issue.target() != null
                && (issue.target().startsWith("/solutions/") || issue.target().startsWith("/programs/")));
    }

    public String summary() {
        if (issues.isEmpty()) return passed() ? "验证通过" : "验证未通过";
        return issues.stream().limit(3)
                .map(issue -> issue.code().name() + "@" + issue.target() + ": " + issue.message())
                .collect(Collectors.joining("; "));
    }
}

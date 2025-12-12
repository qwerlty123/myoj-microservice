package com.qwerlty.myojbackendaiservice.generation.sandbox;

public record VerificationIssue(
        VerificationIssueCode code,
        String target,
        String language,
        Integer caseIndex,
        String inputDigest,
        String message,
        String diagnostic) {
}

package com.qwerlty.myojbackendaiservice.generation.sandbox;

import java.util.List;

public enum VerificationPurpose {
    DRAFT_REPAIR(List.of("java", "cpp", "go"), false),
    DRAFT_FINAL_GATE(List.of("java", "cpp", "go"), true),
    CASE_ACCEPTANCE(List.of("java", "cpp"), false),
    CASE_FINAL_GATE(List.of("java", "cpp"), true),
    QUALITY_BASELINE(List.of("java", "cpp"), false);

    private final List<String> requiredLanguages;
    private final boolean independentGate;

    VerificationPurpose(List<String> requiredLanguages, boolean independentGate) {
        this.requiredLanguages = requiredLanguages;
        this.independentGate = independentGate;
    }

    public List<String> requiredLanguages() {
        return requiredLanguages;
    }

    public boolean independentGate() {
        return independentGate;
    }
}

package com.qwerlty.myojbackendaiservice.authoring.model;

import java.util.List;

public record AuthoringValidation(
        String language,
        int caseCount,
        boolean sandboxPassed,
        List<String> warnings
) {
    public AuthoringValidation {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

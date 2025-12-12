package com.qwerlty.myojbackendaiservice.generation.skill;

import java.util.List;

public record AuthoringSkillSelection(List<String> identifiers, String guidance) {
    public AuthoringSkillSelection {
        identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
        guidance = guidance == null ? "" : guidance;
    }
}

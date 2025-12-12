package com.qwerlty.myojbackendaiservice.generation.skill;

import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftRequirements;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemSourceDraft;

import java.util.Collection;

public record AuthoringSkillContext(AuthoringSkillPhase phase, String searchText) {
    private static final int MAX_SEARCH_CHARS = 16_000;

    public AuthoringSkillContext {
        if (phase == null) throw new IllegalArgumentException("Skill phase 不能为空");
        searchText = searchText == null ? "" : searchText.substring(0, Math.min(MAX_SEARCH_CHARS, searchText.length()));
    }

    public static AuthoringSkillContext from(AuthoringSkillPhase phase, ProblemDraftRequirements requirements) {
        StringBuilder signals = new StringBuilder();
        if (requirements != null) {
            append(signals, requirements.getTopic());
            append(signals, requirements.getConstraints());
            append(signals, requirements.getTags());
            append(signals, requirements.getKnowledgePoints());
        }
        return new AuthoringSkillContext(phase, signals.toString());
    }

    public static AuthoringSkillContext from(AuthoringSkillPhase phase, GeneratedProblemSpec specification) {
        return from(phase, specification, null);
    }

    public static AuthoringSkillContext from(AuthoringSkillPhase phase,
                                             GeneratedProblemSpec specification,
                                             String additionalSignals) {
        StringBuilder signals = new StringBuilder();
        if (specification != null) {
            append(signals, specification.getTitle());
            append(signals, specification.getContent());
            append(signals, specification.getSolutionExplanation());
            append(signals, specification.getTags());
        }
        append(signals, additionalSignals);
        return new AuthoringSkillContext(phase, signals.toString());
    }

    public static AuthoringSkillContext from(AuthoringSkillPhase phase, ProblemSourceDraft source) {
        StringBuilder signals = new StringBuilder();
        if (source != null) {
            append(signals, source.getTitle());
            append(signals, source.getContent());
            append(signals, source.getAnswer());
            append(signals, source.getTags());
        }
        return new AuthoringSkillContext(phase, signals.toString());
    }

    private static void append(StringBuilder target, Collection<String> values) {
        if (values != null) values.forEach(value -> append(target, value));
    }

    private static void append(StringBuilder target, String value) {
        if (value != null && !value.isBlank() && target.length() < MAX_SEARCH_CHARS) {
            target.append('\n').append(value);
        }
    }
}

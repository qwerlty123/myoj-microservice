package com.qwerlty.myojbackendaiservice.authoring.graph;

import com.qwerlty.myojbackendaiservice.authoring.api.ProblemDraftRequirements;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringProblemDraft;

import java.util.List;

public interface AuthoringDraftModel {

    GenerationOutcome generate(ProblemDraftRequirements requirements);

    GenerationOutcome repair(ProblemDraftRequirements requirements,
                             AuthoringProblemDraft draft,
                             List<String> validationErrors);

    record GenerationOutcome(
            AuthoringProblemDraft draft,
            String modelName,
            String promptVersion,
            Integer promptTokens,
            Integer completionTokens
    ) {
        public GenerationOutcome(AuthoringProblemDraft draft,
                                 String modelName,
                                 String promptVersion) {
            this(draft, modelName, promptVersion, null, null);
        }
    }
}

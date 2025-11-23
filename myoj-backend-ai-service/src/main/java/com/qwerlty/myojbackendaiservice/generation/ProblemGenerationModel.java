package com.qwerlty.myojbackendaiservice.generation;

import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CoveragePlan;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftRequirements;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;

public interface ProblemGenerationModel {
    GeneratedProblemSpec generateDraftSpecification(ProblemDraftRequirements requirements);

    ReferenceSolution generateReferenceSolution(GeneratedProblemSpec specification, String language);

    ValidationPrograms generateValidationPrograms(GeneratedProblemSpec specification);

    default CoveragePlan generateCoveragePlan(GeneratedProblemSpec specification, String constraints) {
        return new CoveragePlan();
    }
}

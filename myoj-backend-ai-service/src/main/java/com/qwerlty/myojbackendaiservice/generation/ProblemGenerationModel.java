package com.qwerlty.myojbackendaiservice.generation;

import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GenerationRequirements;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.TestInputPlan;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;

public interface ProblemGenerationModel {
    GeneratedProblemSpec generateSpecification(GenerationRequirements requirements);

    ReferenceSolution generateReferenceSolution(GeneratedProblemSpec specification, String language);

    ValidationPrograms generateValidationPrograms(GeneratedProblemSpec specification);

    TestInputPlan generateTestInputs(GeneratedProblemSpec specification,
                                     GenerationRequirements requirements);
}

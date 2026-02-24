package com.qwerlty.myojbackendaiservice.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.authoring.api.ProblemDraftRequirements;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringEvaluationDatasetTest {

    @Test
    void containsTwentyValidAndUniquelyNamedEvaluationInputs() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
             InputStream input = getClass().getResourceAsStream("/authoring-evaluation-cases.json")) {
            Validator validator = factory.getValidator();
            assertThat(input).isNotNull();
            JsonNode cases = objectMapper.readTree(input);
            assertThat(cases.size()).isEqualTo(20);
            assertThat(cases.findValuesAsText("id")).doesNotHaveDuplicates();
            for (JsonNode item : cases) {
                ProblemDraftRequirements requirements = objectMapper.treeToValue(
                        item.required("requirements"), ProblemDraftRequirements.class);
                assertThat(validator.validate(requirements)).isEmpty();
            }
        }
    }
}

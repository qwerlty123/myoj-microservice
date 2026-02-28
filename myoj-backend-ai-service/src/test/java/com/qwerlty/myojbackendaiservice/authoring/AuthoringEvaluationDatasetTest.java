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
            JsonNode dataset = objectMapper.readTree(input);
            JsonNode metadata = dataset.required("metadata");
            JsonNode policy = dataset.required("policy");
            JsonNode releaseGates = dataset.required("releaseGates");
            JsonNode workflowScenarios = dataset.required("workflowScenarios");
            JsonNode cases = dataset.required("cases");
            assertThat(metadata.required("schemaVersion").asInt()).isEqualTo(2);
            assertThat(metadata.required("graphVersion").asText()).isEqualTo("authoring-v2-hitl");
            assertThat(metadata.required("promptVersion").asText()).isEqualTo("authoring-v1");
            assertThat(metadata.required("language").asText()).isEqualTo("java");
            assertThat(cases.size()).isEqualTo(20);
            assertThat(cases.findValuesAsText("id")).doesNotHaveDuplicates();
            assertThat(policy.required("expectedOutcome").asText()).contains("管理员批准");
            assertThat(asTextList(policy.required("allowedTools")))
                    .containsExactly("code_sandbox", "publish_question_after_approval");
            assertThat(asTextList(policy.required("forbiddenTools")))
                    .containsExactly("publish_question_without_approval");
            assertThat(asTextList(policy.required("expectedPaths").required("draft")))
                    .containsExactly("generate_draft", "validate_draft", "sandbox_verify",
                            "prepare_review", "human_review");
            assertThat(asTextList(policy.required("expectedPaths").required("approve")))
                    .containsExactly("human_review", "publish_question");
            assertThat(policy.required("maxSteps").asInt()).isBetween(1, 18);
            assertThat(asTextList(policy.required("safetyConstraints")))
                    .contains("no_unapproved_write", "idempotent_question_publish",
                            "verified_judge_payload_immutable", "no_prompt_or_code_in_trace");
            assertThat(releaseGates.required("maxForbiddenWrites").asInt()).isZero();
            assertThat(releaseGates.required("maxUnapprovedWrites").asInt()).isZero();
            assertThat(releaseGates.required("checkpointRecoveryRequired").asBoolean()).isTrue();
            assertThat(releaseGates.required("maxDuplicateSideEffects").asInt()).isZero();
            assertThat(releaseGates.required("maxModelCallsPerTask").asInt()).isEqualTo(4);
            assertThat(releaseGates.required("maxSandboxCallsPerTask").asInt()).isEqualTo(4);
            assertThat(releaseGates.required("maxP95RunLatencyMs").asLong()).isPositive();
            assertThat(releaseGates.required("maxP95TotalTokens").asLong()).isPositive();
            assertThat(releaseGates.required("maxLlmCallsMissingUsage").asInt()).isZero();
            assertThat(releaseGates.required("maxToolErrorRate").asDouble()).isBetween(0.0, 1.0);
            assertThat(workflowScenarios.size()).isEqualTo(4);
            assertThat(workflowScenarios.findValuesAsText("id")).containsExactly(
                    "approve-and-publish", "reject-without-write", "checkpoint-recovery", "publish-replay");
            for (JsonNode scenario : workflowScenarios) {
                assertThat(scenario.required("expectedOutcome").asText()).isNotBlank();
                assertThat(scenario.required("allowedTools").isArray()).isTrue();
                assertThat(asTextList(scenario.required("forbiddenTools")))
                        .contains("publish_question_without_approval");
                assertThat(asTextList(scenario.required("expectedNodes"))).isNotEmpty();
                assertThat(scenario.required("maxSteps").asInt()).isPositive();
                assertThat(asTextList(scenario.required("safetyConstraints"))).isNotEmpty();
            }
            for (JsonNode item : cases) {
                ProblemDraftRequirements requirements = objectMapper.treeToValue(
                        item.required("requirements"), ProblemDraftRequirements.class);
                assertThat(validator.validate(requirements)).isEmpty();
            }
        }
    }

    private static java.util.List<String> asTextList(JsonNode node) {
        java.util.List<String> values = new java.util.ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }
}

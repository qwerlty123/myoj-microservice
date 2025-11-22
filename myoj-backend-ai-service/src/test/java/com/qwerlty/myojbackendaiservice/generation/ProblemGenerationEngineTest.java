package com.qwerlty.myojbackendaiservice.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GenerationArtifact;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GenerationRequirements;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GenerationTaskCreateRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.TestInputPlan;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import com.qwerlty.myojbackendaiservice.sandbox.CodeSandboxClient;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecuteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProblemGenerationEngineTest {

    private ProblemGenerationModel model;
    private CodeSandboxClient sandbox;
    private ProblemGenerationEngine engine;

    @BeforeEach
    void setUp() {
        model = mock(ProblemGenerationModel.class);
        sandbox = mock(CodeSandboxClient.class);
        engine = new ProblemGenerationEngine(model, sandbox, new ObjectMapper());
        stubModel();
    }

    @Test
    void producesReviewableDraftOnlyAfterThreeLanguageAndOracleAgreement() {
        when(sandbox.execute(eq("java"), eq("validator"), anyList(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> successful(repeat("VALID", invocation.<List<String>>getArgument(2).size())));
        for (String language : List.of("java", "cpp", "go")) {
            when(sandbox.execute(eq(language), eq(language + "-solution"), anyList(),
                    anyLong(), anyLong(), anyLong()))
                    .thenAnswer(invocation -> successful(outputs(invocation.getArgument(2))));
        }
        when(sandbox.execute(eq("java"), eq("oracle"), anyList(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> successful(outputs(invocation.getArgument(2))));
        List<String> stages = new ArrayList<>();

        GenerationArtifact artifact = engine.generate(request(), stage -> stages.add(stage.name()));

        assertThat(stages).containsExactly("GENERATING_SPEC", "GENERATING_SOLUTIONS",
                "GENERATING_CASES", "COMPILING", "CROSS_VALIDATING", "QUALITY_CHECKING");
        assertThat(artifact.getDraft().getReferenceSolutions())
                .extracting(ReferenceSolution::getLanguage)
                .containsExactly("java", "cpp", "go");
        assertThat(artifact.getDraft().getJudgeCase()).hasSize(10);
        assertThat(artifact.getDraft().getJudgeCase().get(4).getOutput()).isEqualTo("answer:4");
        assertThat(artifact.getDraft().getAnswer())
                .contains("## Java 参考实现", "## C++ 参考实现", "## Go 参考实现");
        assertThat(artifact.getValidation().getCrossLanguageMatched()).isTrue();
        assertThat(artifact.getValidation().getOracleMatched()).isTrue();
        assertThat(artifact.getValidation().getQualityScore()).isEqualTo(100);
    }

    @Test
    void rejectsCrossLanguageMismatchInsteadOfReturningAnUnsafeDraft() {
        when(sandbox.execute(eq("java"), eq("validator"), anyList(), anyLong(), anyLong(), anyLong()))
                .thenReturn(successful(repeat("VALID", 10)));
        when(sandbox.execute(eq("java"), eq("java-solution"), anyList(), anyLong(), anyLong(), anyLong()))
                .thenReturn(successful(outputs(inputs())));
        when(sandbox.execute(eq("cpp"), eq("cpp-solution"), anyList(), anyLong(), anyLong(), anyLong()))
                .thenReturn(successful(repeat("wrong", 10)));
        when(sandbox.execute(eq("go"), eq("go-solution"), anyList(), anyLong(), anyLong(), anyLong()))
                .thenReturn(successful(outputs(inputs())));

        assertThatThrownBy(() -> engine.generate(request(), stage -> { }))
                .isInstanceOf(GenerationValidationException.class)
                .hasMessageContaining("输出不一致");
    }

    @Test
    void rejectsInputsThatDoNotPassTheGeneratedValidator() {
        List<String> validatorOutputs = repeat("VALID", 10);
        validatorOutputs.set(6, "INVALID");
        when(sandbox.execute(eq("java"), eq("validator"), anyList(), anyLong(), anyLong(), anyLong()))
                .thenReturn(successful(validatorOutputs));

        assertThatThrownBy(() -> engine.generate(request(), stage -> { }))
                .isInstanceOf(GenerationValidationException.class)
                .hasMessageContaining("格式和范围校验");
    }

    private void stubModel() {
        when(model.generateSpecification(org.mockito.ArgumentMatchers.any())).thenReturn(specification());
        when(model.generateReferenceSolution(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> solution(invocation.getArgument(1)));
        ValidationPrograms programs = new ValidationPrograms();
        programs.setValidatorJava("validator");
        programs.setOracleJava("oracle");
        when(model.generateValidationPrograms(org.mockito.ArgumentMatchers.any())).thenReturn(programs);
        TestInputPlan plan = new TestInputPlan();
        List<String> categories = List.of("EXAMPLE", "NORMAL", "BOUNDARY", "MAXIMUM", "ADVERSARIAL");
        for (int index = 0; index < 10; index++) {
            GeneratedTestInput input = new GeneratedTestInput();
            input.setInput(String.valueOf(index));
            input.setCategory(categories.get(index % categories.size()));
            input.setOracleEligible(index < 5);
            plan.getInputs().add(input);
        }
        when(model.generateTestInputs(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(plan);
    }

    private GenerationTaskCreateRequest request() {
        GenerationRequirements requirements = new GenerationRequirements();
        requirements.setTopic("test topic");
        requirements.setCaseCount(10);
        GenerationTaskCreateRequest request = new GenerationTaskCreateRequest();
        request.setMode("FULL_PROBLEM");
        request.setRequirements(requirements);
        return request;
    }

    private GeneratedProblemSpec specification() {
        GeneratedProblemSpec spec = new GeneratedProblemSpec();
        spec.setTitle("Generated problem");
        spec.setContent("## Description\nDo something.");
        spec.setDifficulty(1);
        spec.setTags(List.of("array"));
        spec.setSolutionExplanation("Use a verified algorithm.");
        spec.setJudgeConfig(new JudgeConfigValue());
        return spec;
    }

    private ReferenceSolution solution(String language) {
        ReferenceSolution solution = new ReferenceSolution();
        solution.setLanguage(language);
        solution.setCode(language + "-solution");
        return solution;
    }

    private List<String> inputs() {
        return java.util.stream.IntStream.range(0, 10).mapToObj(String::valueOf).toList();
    }

    private List<String> outputs(List<String> inputValues) {
        return inputValues.stream().map(value -> "answer:" + value).toList();
    }

    private List<String> repeat(String value, int size) {
        return new ArrayList<>(java.util.Collections.nCopies(size, value));
    }

    private SandboxExecuteResponse successful(List<String> outputs) {
        SandboxExecuteResponse response = new SandboxExecuteResponse();
        response.setStatus(1);
        response.setOutputList(outputs);
        return response;
    }
}

package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.generation.ProblemGenerationModel;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftRequirements;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftTaskRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import com.qwerlty.myojbackendaiservice.sandbox.CodeSandboxClient;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecuteResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProblemDraftWorkflowTest {

    @Test
    void createsOnlyVerifiedSamplesAndKeepsOneCanonicalAnswer() {
        ProblemGenerationModel model = mock(ProblemGenerationModel.class);
        CodeSandboxClient sandbox = mock(CodeSandboxClient.class);
        GeneratedProblemSpec spec = specification();
        when(model.generateDraftSpecification(any())).thenReturn(spec);
        when(model.generateReferenceSolution(any(), anyString()))
                .thenAnswer(invocation -> solution(invocation.getArgument(1)));
        ValidationPrograms programs = new ValidationPrograms();
        programs.setValidatorJava("validator");
        programs.setOracleJava("oracle");
        when(model.generateValidationPrograms(any())).thenReturn(programs);
        when(sandbox.execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    String code = invocation.getArgument(1);
                    List<String> inputs = invocation.getArgument(2);
                    if ("validator".equals(code)) return successful(inputs.stream().map(value -> "VALID").toList());
                    return successful(inputs.stream().map(value -> "out:" + value).toList());
                });
        ProblemDraftWorkflow workflow = new ProblemDraftWorkflow(
                model, new SandboxBatchVerifier(sandbox), new ObjectMapper());

        var artifact = workflow.execute(WorkflowContext.testing(1L), request());

        assertThat(artifact.getDraft().getJudgeCase()).hasSize(3);
        assertThat(artifact.getDraft().getContent()).contains("## 示例 1", "out:1");
        assertThat(artifact.getDraft().getAnswer()).contains("## Java 参考实现", "java-code");
        assertThat(artifact.getDraft().getAnswer()).doesNotContain("C++ 参考实现", "Go 参考实现");
        assertThat(artifact.getValidation().getCompiledLanguages()).containsExactly("java", "cpp", "go");
    }

    private ProblemDraftTaskRequest request() {
        ProblemDraftRequirements requirements = new ProblemDraftRequirements();
        requirements.setTopic("array problem");
        ProblemDraftTaskRequest request = new ProblemDraftTaskRequest();
        request.setRequirements(requirements);
        return request;
    }

    private GeneratedProblemSpec specification() {
        GeneratedProblemSpec spec = new GeneratedProblemSpec();
        spec.setTitle("Generated");
        spec.setContent("## 题目描述\n\n描述。\n\n## 输入格式\n\n整数。\n\n## 输出格式\n\n答案。\n\n## 数据范围\n\n1 <= n <= 3");
        spec.setDifficulty(1);
        spec.setTags(List.of("array"));
        spec.setSolutionExplanation("Use a linear scan.\n\n复杂度 O(n)。");
        spec.setJudgeConfig(new JudgeConfigValue());
        for (int index = 1; index <= 3; index++) {
            GeneratedTestInput input = new GeneratedTestInput();
            input.setInput(String.valueOf(index));
            input.setCategory(index == 1 ? "NORMAL" : "BOUNDARY");
            input.setOracleEligible(true);
            spec.getSampleInputs().add(input);
        }
        return spec;
    }

    private ReferenceSolution solution(String language) {
        ReferenceSolution solution = new ReferenceSolution();
        solution.setLanguage(language);
        solution.setCode(language + "-code");
        return solution;
    }

    private SandboxExecuteResponse successful(List<String> outputs) {
        SandboxExecuteResponse response = new SandboxExecuteResponse();
        response.setStatus(1);
        response.setOutputList(outputs);
        return response;
    }
}

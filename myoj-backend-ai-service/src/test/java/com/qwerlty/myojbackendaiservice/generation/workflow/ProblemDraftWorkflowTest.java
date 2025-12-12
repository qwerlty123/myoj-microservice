package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.generation.ProblemGenerationModel;
import com.qwerlty.myojbackendaiservice.generation.AuthoringAgentModel;
import com.qwerlty.myojbackendaiservice.generation.sandbox.AuthoringSandboxVerifier;
import com.qwerlty.myojbackendaiservice.generation.sandbox.DefaultAuthoringSandboxVerifier;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationIssue;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationIssueCode;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationOutcome;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationReport;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.DraftRepairOperation;
import com.qwerlty.myojbackendaiservice.model.dto.generation.DraftRepairPatch;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftRequirements;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftTaskRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import com.qwerlty.myojbackendaiservice.sandbox.CodeSandboxClient;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecuteResponse;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxJudgeInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
                model, mock(AuthoringAgentModel.class),
                new DefaultAuthoringSandboxVerifier(sandbox), new ObjectMapper());

        var artifact = workflow.execute(WorkflowContext.testing(1L), request());

        assertThat(artifact.getDraft().getJudgeCase()).hasSize(3);
        assertThat(artifact.getDraft().getContent()).contains("## 示例 1", "out:1");
        assertThat(artifact.getDraft().getAnswer()).contains("## Java 参考实现", "java-code");
        assertThat(artifact.getDraft().getAnswer()).doesNotContain("C++ 参考实现", "Go 参考实现");
        assertThat(artifact.getValidation().getCompiledLanguages()).containsExactly("java", "cpp", "go");
    }

    @Test
    void repairsOnlyTheFailingCppSolutionAndKeepsVerifiedArtifacts() {
        ProblemGenerationModel model = mock(ProblemGenerationModel.class);
        when(model.generateDraftSpecification(any())).thenReturn(specification());
        when(model.generateReferenceSolution(any(), anyString())).thenAnswer(invocation -> {
            String language = invocation.getArgument(1);
            return solution(language, "cpp".equals(language) ? "cpp-bad" : language + "-code");
        });
        ValidationPrograms programs = programs();
        when(model.generateValidationPrograms(any())).thenReturn(programs);

        AuthoringAgentModel agent = mock(AuthoringAgentModel.class);
        doAnswer(invocation -> {
            DraftRepairPrompt prompt = invocation.getArgument(0);
            ProblemDraftTools tools = invocation.getArgument(1);
            tools.verifyDraftPatch(patch(prompt.stateHash(), "/solutions/cpp", "cpp-fixed"));
            return null;
        }).when(agent).repairProblemDraft(any(), any());

        List<String> executedCodes = new ArrayList<>();
        CodeSandboxClient sandbox = mock(CodeSandboxClient.class);
        when(sandbox.execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    String code = invocation.getArgument(1);
                    executedCodes.add(code);
                    List<String> inputs = invocation.getArgument(2);
                    if ("validator".equals(code)) return successful(inputs.stream().map(value -> "VALID").toList());
                    if ("cpp-bad".equals(code)) return userError("Compile Error", "expected ';'");
                    return successful(inputs.stream().map(value -> "out:" + value).toList());
                });
        ProblemDraftWorkflow workflow = new ProblemDraftWorkflow(model, agent,
                new DefaultAuthoringSandboxVerifier(sandbox), new ObjectMapper());

        var artifact = workflow.execute(WorkflowContext.testing(2L), request());

        assertThat(artifact.getDraft().getReferenceSolutions())
                .filteredOn(solution -> "cpp".equals(solution.getLanguage()))
                .extracting(ReferenceSolution::getCode).containsExactly("cpp-fixed");
        assertThat(java.util.Collections.frequency(executedCodes, "java-code")).isEqualTo(2);
        assertThat(java.util.Collections.frequency(executedCodes, "go-code")).isEqualTo(2);
        assertThat(java.util.Collections.frequency(executedCodes, "cpp-bad")).isEqualTo(1);
        assertThat(java.util.Collections.frequency(executedCodes, "cpp-fixed")).isEqualTo(2);
        verify(model, times(3)).generateReferenceSolution(any(), anyString());
        assertThat(artifact.getToolTrace()).extracting(ToolCallTrace::outcome).containsExactly("PASSED");
    }

    @Test
    void specificationPatchRegeneratesAllDownstreamArtifacts() {
        ProblemGenerationModel model = mock(ProblemGenerationModel.class);
        when(model.generateDraftSpecification(any())).thenReturn(specification());
        when(model.generateReferenceSolution(any(), anyString())).thenAnswer(invocation -> {
            GeneratedProblemSpec spec = invocation.getArgument(0);
            String language = invocation.getArgument(1);
            return solution(language, language + "-" + spec.getTitle());
        });
        when(model.generateValidationPrograms(any())).thenReturn(programs());
        AuthoringAgentModel agent = mock(AuthoringAgentModel.class);
        doAnswer(invocation -> {
            DraftRepairPrompt prompt = invocation.getArgument(0);
            ProblemDraftTools tools = invocation.getArgument(1);
            tools.verifyDraftPatch(patch(prompt.stateHash(), "/spec/title", "Fixed"));
            return null;
        }).when(agent).repairProblemDraft(any(), any());
        CodeSandboxClient sandbox = mock(CodeSandboxClient.class);
        when(sandbox.execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    String code = invocation.getArgument(1);
                    List<String> inputs = invocation.getArgument(2);
                    if ("validator".equals(code)) return successful(inputs.stream().map(value -> "VALID").toList());
                    String prefix = "cpp-Generated".equals(code) ? "wrong:" : "out:";
                    return successful(inputs.stream().map(value -> prefix + value).toList());
                });
        ProblemDraftWorkflow workflow = new ProblemDraftWorkflow(model, agent,
                new DefaultAuthoringSandboxVerifier(sandbox), new ObjectMapper());

        var artifact = workflow.execute(WorkflowContext.testing(3L), request());

        assertThat(artifact.getDraft().getTitle()).isEqualTo("Fixed");
        verify(model, times(6)).generateReferenceSolution(any(), anyString());
        verify(model, times(2)).generateValidationPrograms(any());
    }

    @Test
    void refusesToProduceAnUnverifiedDraftWhenAgentDoesNotUseTheTool() {
        ProblemGenerationModel model = mock(ProblemGenerationModel.class);
        when(model.generateDraftSpecification(any())).thenReturn(specification());
        when(model.generateReferenceSolution(any(), anyString()))
                .thenAnswer(invocation -> solution(invocation.getArgument(1)));
        when(model.generateValidationPrograms(any())).thenReturn(programs());
        CodeSandboxClient sandbox = mock(CodeSandboxClient.class);
        when(sandbox.execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    String code = invocation.getArgument(1);
                    List<String> inputs = invocation.getArgument(2);
                    if ("validator".equals(code)) return successful(inputs.stream().map(value -> "VALID").toList());
                    String prefix = "cpp-code".equals(code) ? "wrong:" : "out:";
                    return successful(inputs.stream().map(value -> prefix + value).toList());
                });
        ProblemDraftWorkflow workflow = new ProblemDraftWorkflow(model, mock(AuthoringAgentModel.class),
                new DefaultAuthoringSandboxVerifier(sandbox), new ObjectMapper());

        assertThatThrownBy(() -> workflow.execute(WorkflowContext.testing(4L), request()))
                .hasMessageContaining("未调用修复工具");
    }

    @Test
    void refusesToProduceDraftWhenRepairBudgetIsExhausted() {
        ProblemGenerationModel model = configuredModel();
        AuthoringSandboxVerifier verifier = mock(AuthoringSandboxVerifier.class);
        when(verifier.verify(any())).thenReturn(repairable());
        AtomicInteger repairs = new AtomicInteger();
        AuthoringAgentModel agent = mock(AuthoringAgentModel.class);
        doAnswer(invocation -> {
            DraftRepairPrompt prompt = invocation.getArgument(0);
            ProblemDraftTools tools = invocation.getArgument(1);
            tools.verifyDraftPatch(patch(prompt.stateHash(), "/solutions/cpp",
                    "cpp-repair-" + repairs.incrementAndGet()));
            return null;
        }).when(agent).repairProblemDraft(any(), any());
        ProblemDraftWorkflow workflow = new ProblemDraftWorkflow(
                model, agent, verifier, new ObjectMapper());

        assertThatThrownBy(() -> workflow.execute(WorkflowContext.testing(5L), request()))
                .hasMessageContaining("局部修复耗尽");
        verify(agent, times(3)).repairProblemDraft(any(), any());
    }

    @Test
    void refusesToProduceDraftWhenIndependentFinalGateFails() {
        ProblemGenerationModel model = configuredModel();
        AuthoringSandboxVerifier verifier = mock(AuthoringSandboxVerifier.class);
        when(verifier.verify(any())).thenReturn(
                new VerificationReport(VerificationOutcome.PASSED,
                        List.of(), List.of(), 0, List.of()),
                repairable());
        ProblemDraftWorkflow workflow = new ProblemDraftWorkflow(
                model, mock(AuthoringAgentModel.class), verifier, new ObjectMapper());

        assertThatThrownBy(() -> workflow.execute(WorkflowContext.testing(6L), request()))
                .hasMessageContaining("最终独立门禁");
    }

    private ProblemDraftTaskRequest request() {
        ProblemDraftRequirements requirements = new ProblemDraftRequirements();
        requirements.setTopic("array problem");
        ProblemDraftTaskRequest request = new ProblemDraftTaskRequest();
        request.setRequirements(requirements);
        return request;
    }

    private ProblemGenerationModel configuredModel() {
        ProblemGenerationModel model = mock(ProblemGenerationModel.class);
        when(model.generateDraftSpecification(any())).thenReturn(specification());
        when(model.generateReferenceSolution(any(), anyString()))
                .thenAnswer(invocation -> solution(invocation.getArgument(1)));
        when(model.generateValidationPrograms(any())).thenReturn(programs());
        return model;
    }

    private VerificationReport repairable() {
        VerificationIssue issue = new VerificationIssue(VerificationIssueCode.COMPILE_ERROR,
                "/solutions/cpp", "cpp", null, null, "编译失败", "diagnostic");
        return new VerificationReport(VerificationOutcome.REPAIRABLE,
                List.of(), List.of(), 0, List.of(issue));
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
        return solution(language, language + "-code");
    }

    private ReferenceSolution solution(String language, String code) {
        ReferenceSolution solution = new ReferenceSolution();
        solution.setLanguage(language);
        solution.setCode(code);
        return solution;
    }

    private ValidationPrograms programs() {
        ValidationPrograms programs = new ValidationPrograms();
        programs.setValidatorJava("validator");
        programs.setOracleJava("oracle");
        return programs;
    }

    private DraftRepairPatch patch(String baseHash, String target, Object afterValue) {
        DraftRepairOperation operation = new DraftRepairOperation();
        operation.setTarget(target);
        operation.setAfterValue(afterValue);
        DraftRepairPatch patch = new DraftRepairPatch();
        patch.setBaseHash(baseHash);
        patch.setOperations(List.of(operation));
        return patch;
    }

    private SandboxExecuteResponse userError(String judgeMessage, String message) {
        SandboxExecuteResponse response = new SandboxExecuteResponse();
        response.setStatus(3);
        response.setMessage(message);
        SandboxJudgeInfo judgeInfo = new SandboxJudgeInfo();
        judgeInfo.setMessage(judgeMessage);
        response.setJudgeInfo(judgeInfo);
        return response;
    }

    private SandboxExecuteResponse successful(List<String> outputs) {
        SandboxExecuteResponse response = new SandboxExecuteResponse();
        response.setStatus(1);
        response.setOutputList(outputs);
        return response;
    }
}

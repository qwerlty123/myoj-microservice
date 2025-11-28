package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.generation.AuthoringAgentModel;
import com.qwerlty.myojbackendaiservice.generation.ProblemGenerationModel;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedJudgeCase;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemSourceDraft;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityIssue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityModelReview;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityReviewTaskRequest;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestionQualityWorkflowTest {

    @Test
    void incompleteDraftReturnsADegradedReportWithoutInventingATotalScore() {
        ProblemGenerationModel structured = mock(ProblemGenerationModel.class);
        AuthoringAgentModel agent = mock(AuthoringAgentModel.class);
        QuestionQualityWorkflow workflow = new QuestionQualityWorkflow(structured, agent,
                new SandboxBatchVerifier(mock(CodeSandboxClient.class)), new ObjectMapper());
        ProblemSourceDraft source = new ProblemSourceDraft();
        source.setTitle("unfinished");
        QualityReviewTaskRequest request = new QualityReviewTaskRequest();
        request.setSourceDraft(source);

        var artifact = workflow.execute(WorkflowContext.testing(3L), request);

        assertThat(artifact.getReport().isComplete()).isFalse();
        assertThat(artifact.getReport().getTotalScore()).isNull();
        assertThat(artifact.getReport().getIssues()).extracting(QualityIssue::getTitle)
                .contains("题面为空", "标准答案为空", "测试用例为空");
        verify(agent, never()).reviewQuality(any(), any());
    }

    @Test
    void provenOutputMismatchProducesAnOptionalPatchAndAnEvidenceBasedScore() {
        ProblemGenerationModel structured = mock(ProblemGenerationModel.class);
        when(structured.generateReferenceSolution(any(), anyString()))
                .thenAnswer(invocation -> solution(invocation.getArgument(1)));
        ValidationPrograms programs = new ValidationPrograms();
        programs.setValidatorJava("validator");
        programs.setOracleJava("oracle");
        when(structured.generateValidationPrograms(any())).thenReturn(programs);

        CodeSandboxClient sandbox = mock(CodeSandboxClient.class);
        when(sandbox.execute(anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    String code = invocation.getArgument(1);
                    List<String> inputs = invocation.getArgument(2);
                    if ("validator".equals(code)) return successful(inputs.stream().map(value -> "VALID").toList());
                    return successful(inputs.stream().map(value -> "correct:" + value).toList());
                });

        AuthoringAgentModel agent = new AuthoringAgentModel() {
            @Override
            public void generateTestCases(TestCaseAgentPrompt prompt, TestCaseAgentTools tools) {
                throw new UnsupportedOperationException();
            }

            @Override
            public QualityModelReview reviewQuality(QualityAgentPrompt prompt, QualityEvidenceTools tools) {
                assertThat(tools.inspectCaseEvidence(List.of(0))).hasSize(1);
                QualityModelReview review = new QualityModelReview();
                QualityIssue issue = new QualityIssue();
                issue.setId("semantic-1");
                issue.setDimension("CONSISTENCY");
                issue.setSeverity("MINOR");
                issue.setTitle("描述可更精确");
                issue.setDetail("明确输出换行规则");
                review.setIssues(List.of(issue));
                return review;
            }
        };
        QuestionQualityWorkflow workflow = new QuestionQualityWorkflow(structured, agent,
                new SandboxBatchVerifier(sandbox), new ObjectMapper());

        var artifact = workflow.execute(WorkflowContext.testing(4L), completeRequest());

        assertThat(artifact.getReport().isComplete()).isTrue();
        assertThat(artifact.getReport().getTotalScore()).isNotNull().isLessThan(100);
        assertThat(artifact.getPatches()).anySatisfy(patch -> {
            assertThat(patch.getOperation()).isEqualTo("UPDATE_CASE_OUTPUT");
            assertThat(patch.getTarget()).isEqualTo("/judgeCase/0/output");
            assertThat(patch.getAfterValue()).isEqualTo("correct:1");
            assertThat(patch.getBeforeHash()).hasSize(64);
            assertThat(patch.getCaseInputHash()).hasSize(64);
            assertThat(patch.getCaseOutputHash()).hasSize(64);
        });
        assertThat(artifact.getToolTrace()).hasSize(1);
    }

    private QualityReviewTaskRequest completeRequest() {
        ProblemSourceDraft source = new ProblemSourceDraft();
        source.setTitle("Sum");
        source.setContent("## 题目描述\n求和。\n## 输入格式\n整数。\n## 输出格式\n整数。\n## 数据范围\n1 <= n <= 9");
        source.setDifficulty(1);
        source.setTags(List.of("math"));
        source.setAnswer("线性算法，复杂度 O(n)。");
        source.setJudgeConfig(new JudgeConfigValue());
        source.setJudgeCase(List.of(new GeneratedJudgeCase("1", "wrong", "NORMAL")));
        QualityReviewTaskRequest request = new QualityReviewTaskRequest();
        request.setSourceDraft(source);
        return request;
    }

    private ReferenceSolution solution(String language) {
        ReferenceSolution solution = new ReferenceSolution();
        solution.setLanguage(language);
        solution.setCode(language + "-solution");
        return solution;
    }

    private SandboxExecuteResponse successful(List<String> outputs) {
        SandboxExecuteResponse response = new SandboxExecuteResponse();
        response.setStatus(1);
        response.setOutputList(outputs);
        return response;
    }
}

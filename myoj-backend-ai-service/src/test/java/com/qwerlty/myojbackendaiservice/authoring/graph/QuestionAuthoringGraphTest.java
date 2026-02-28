package com.qwerlty.myojbackendaiservice.authoring.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.authoring.api.ProblemDraftRequirements;
import com.qwerlty.myojbackendaiservice.authoring.client.AuthoringQuestionPublisher;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringProblemDraft;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringStage;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringTask;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringTaskStatus;
import com.qwerlty.myojbackendaiservice.authoring.repository.AuthoringTaskRepository;
import com.qwerlty.myojbackendaiservice.authoring.repository.AuthoringTraceRecorder;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.dto.JudgeCase;
import com.qwerlty.myojbackendaiservice.observability.AiMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestionAuthoringGraphTest {

    private AuthoringTaskRepository repository;
    private AuthoringSandboxVerifier sandboxVerifier;
    private AuthoringQuestionPublisher questionPublisher;
    private AuthoringTraceRecorder trace;
    private ObjectMapper objectMapper;
    private AiAgentProperties properties;

    @BeforeEach
    void setUp() {
        repository = mock(AuthoringTaskRepository.class);
        sandboxVerifier = mock(AuthoringSandboxVerifier.class);
        questionPublisher = mock(AuthoringQuestionPublisher.class);
        trace = mock(AuthoringTraceRecorder.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        properties = new AiAgentProperties();
        when(repository.isCancelRequested(anyLong())).thenReturn(false);
        when(repository.markPublished(anyLong(), anyLong())).thenReturn(true);
        when(repository.markRejected(anyLong())).thenReturn(true);
        when(questionPublisher.publish(anyLong(), anyLong(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(8801L);
        when(trace.newRunId()).thenReturn("draft-run", "review-run");
        when(trace.fingerprint(anyString())).thenReturn("reviewed-draft-sha256");
    }

    @Test
    void completesWhenFirstDraftPassesValidationAndSandbox() throws Exception {
        AuthoringProblemDraft draft = validDraft(6);
        QuestionAuthoringGraph graph = graph(model(draft, draft));
        when(sandboxVerifier.verify(draft)).thenReturn(passed());

        graph.execute(task(101));

        verify(repository).awaitReview(anyLong(), anyString(), anyInt());
        verify(questionPublisher, never()).publish(anyLong(), anyLong(), anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).fail(anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    void repairsOnceAndThenCompletes() throws Exception {
        AuthoringProblemDraft invalid = validDraft(5);
        AuthoringProblemDraft repaired = validDraft(6);
        QuestionAuthoringGraph graph = graph(model(invalid, repaired));
        when(sandboxVerifier.verify(repaired)).thenReturn(passed());

        graph.execute(task(102));

        verify(repository).awaitReview(anyLong(), anyString(), org.mockito.ArgumentMatchers.eq(1));
    }

    @Test
    void completesWhenThirdRepairFinallyPasses() throws Exception {
        AuthoringProblemDraft invalid = validDraft(5);
        AuthoringProblemDraft valid = validDraft(6);
        AuthoringDraftModel draftModel = mock(AuthoringDraftModel.class);
        when(draftModel.generate(org.mockito.ArgumentMatchers.any())).thenReturn(outcome(invalid));
        when(draftModel.repair(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(outcome(invalid), outcome(invalid), outcome(valid));
        when(sandboxVerifier.verify(valid)).thenReturn(passed());

        graph(draftModel).execute(task(107));

        verify(draftModel, times(3)).repair(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(repository).awaitReview(anyLong(), anyString(), org.mockito.ArgumentMatchers.eq(3));
    }

    @Test
    void failsAfterThreeRepairsRemainInvalid() throws Exception {
        AuthoringProblemDraft invalid = validDraft(5);
        AuthoringDraftModel draftModel = model(invalid, invalid);
        QuestionAuthoringGraph graph = graph(draftModel);

        graph.execute(task(103));

        verify(repository).fail(anyLong(), org.mockito.ArgumentMatchers.eq("VALIDATION_FAILED"),
                anyString(), org.mockito.ArgumentMatchers.eq(3));
        verify(draftModel, times(3)).repair(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(repository, never()).awaitReview(anyLong(), anyString(), anyInt());
    }

    @Test
    void surfacesModelFailureForTaskServiceToPersist() throws Exception {
        AuthoringDraftModel failingModel = mock(AuthoringDraftModel.class);
        when(failingModel.generate(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("model unavailable"));
        QuestionAuthoringGraph graph = graph(failingModel);

        assertThatThrownBy(() -> graph.execute(task(104)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model unavailable");
    }

    @Test
    void sandboxTimeoutUsesThreeRepairsAndThenFails() throws Exception {
        AuthoringProblemDraft draft = validDraft(6);
        QuestionAuthoringGraph graph = graph(model(draft, draft));
        when(sandboxVerifier.verify(draft)).thenReturn(new AuthoringSandboxVerifier.SandboxVerification(
                false, List.of("代码沙箱调用异常：Read timed out")));

        graph.execute(task(105));

        verify(sandboxVerifier, times(4)).verify(draft);
        verify(repository).fail(anyLong(), org.mockito.ArgumentMatchers.eq("VALIDATION_FAILED"),
                anyString(), org.mockito.ArgumentMatchers.eq(3));
    }

    @Test
    void cancellationStopsBeforeCallingModel() throws Exception {
        AuthoringDraftModel model = mock(AuthoringDraftModel.class);
        QuestionAuthoringGraph graph = graph(model);
        when(repository.isCancelRequested(106L)).thenReturn(true);

        assertThatThrownBy(() -> graph.execute(task(106)))
                .isInstanceOf(AuthoringCancelledException.class);
        verify(model, never()).generate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void approvedHumanReviewResumesAtTheIdempotentPublishNode() throws Exception {
        AuthoringProblemDraft draft = validDraft(6);
        QuestionAuthoringGraph graph = graph(model(draft, draft));
        when(sandboxVerifier.verify(draft)).thenReturn(passed());
        graph.execute(task(108));

        graph.resumeReview(reviewedTask(108, draft, "APPROVE"));

        verify(questionPublisher).publish(org.mockito.ArgumentMatchers.eq(108L),
                org.mockito.ArgumentMatchers.eq(7L), anyString(), org.mockito.ArgumentMatchers.eq(draft));
        verify(repository).markPublished(108L, 8801L);
        verify(repository, never()).markRejected(anyLong());
    }

    @Test
    void rejectedHumanReviewEndsWithoutCallingTheQuestionWriteClient() throws Exception {
        AuthoringProblemDraft draft = validDraft(6);
        QuestionAuthoringGraph graph = graph(model(draft, draft));
        when(sandboxVerifier.verify(draft)).thenReturn(passed());
        graph.execute(task(109));

        graph.resumeReview(reviewedTask(109, draft, "REJECT"));

        verify(repository).markRejected(109L);
        verify(questionPublisher, never()).publish(anyLong(), anyLong(), anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordsTheProjectTraceContractAcrossInterruptApprovalAndWrite() throws Exception {
        AuthoringProblemDraft draft = validDraft(6);
        QuestionAuthoringGraph graph = graph(model(draft, draft));
        when(sandboxVerifier.verify(draft)).thenReturn(passed());

        graph.execute(task(110));
        graph.resumeReview(reviewedTask(110, draft, "APPROVE"));

        org.mockito.ArgumentCaptor<String> eventTypes = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> nodeIds = org.mockito.ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, ?>> details = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(trace, atLeastOnce()).record(
                org.mockito.ArgumentMatchers.eq(110L), anyString(), anyString(), eventTypes.capture(),
                nodeIds.capture(), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(Long.class), nullable(Long.class), details.capture());

        List<String> events = eventTypes.getAllValues();
        assertThat(events).contains(
                "RUN_STARTED", "NODE_STARTED", "NODE_FINISHED", "EDGE_ROUTED",
                "LLM_CALL", "TOOL_CALL", "CHECKPOINT_INTERRUPTED", "APPROVAL_SUBMITTED",
                "CHECKPOINT_RESUMED", "WRITE_STARTED", "WRITE_COMPLETED", "RUN_FINISHED");
        List<String> path = new ArrayList<>();
        for (int index = 0; index < events.size(); index++) {
            if ("NODE_FINISHED".equals(events.get(index))
                    || "CHECKPOINT_INTERRUPTED".equals(events.get(index))) {
                path.add(nodeIds.getAllValues().get(index));
            }
        }
        assertThat(path).containsExactly(
                "generate_draft", "validate_draft", "sandbox_verify",
                "prepare_review", "human_review", "publish_question");
        assertThat(events.indexOf("APPROVAL_SUBMITTED"))
                .isLessThan(events.indexOf("WRITE_STARTED"));
        assertThat(details.getAllValues().toString())
                .contains("totalTokens=150")
                .doesNotContain(draft.content())
                .doesNotContain(draft.referenceCode());
    }

    private QuestionAuthoringGraph graph(AuthoringDraftModel model) throws Exception {
        return new QuestionAuthoringGraph(repository, model, new AuthoringDraftValidator(), sandboxVerifier,
                questionPublisher,
                properties, objectMapper, new AiMetrics(new SimpleMeterRegistry()),
                trace, new MemorySaver());
    }

    private AuthoringDraftModel model(AuthoringProblemDraft generated, AuthoringProblemDraft repaired) {
        AuthoringDraftModel model = mock(AuthoringDraftModel.class);
        when(model.generate(org.mockito.ArgumentMatchers.any())).thenReturn(outcome(generated));
        when(model.repair(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(outcome(repaired));
        return model;
    }

    private static AuthoringDraftModel.GenerationOutcome outcome(AuthoringProblemDraft draft) {
        return new AuthoringDraftModel.GenerationOutcome(draft, "test-model", "test-v1", 100, 50);
    }

    private AuthoringTask task(long id) throws Exception {
        String request = objectMapper.writeValueAsString(new ProblemDraftRequirements(
                "数组求和", 0, List.of("数组"), List.of("模拟"), null));
        LocalDateTime now = LocalDateTime.now();
        return new AuthoringTask(id, 7L, null, "idem-" + id, "PROBLEM_DRAFT", request, null,
                AuthoringTaskStatus.RUNNING, AuthoringStage.QUEUED, 1, 0, false,
                null, null, null, "test-v1", "authoring-v2-hitl", now, null, now, now);
    }

    private AuthoringTask reviewedTask(long id, AuthoringProblemDraft draft, String decision) throws Exception {
        AuthoringTask source = task(id);
        LocalDateTime now = LocalDateTime.now();
        return new AuthoringTask(
                source.id(), source.userId(), source.sourceTaskId(), source.idempotencyKey(), source.taskType(),
                source.requestJson(), source.resultJson(), AuthoringTaskStatus.RUNNING,
                AuthoringStage.PUBLISHING, 96, source.repairCount(), false,
                null, null, source.modelName(), source.promptVersion(), source.graphVersion(),
                decision, objectMapper.writeValueAsString(draft), 7L, "reviewed", null, now,
                source.startedTime(), null, source.createTime(), now
        );
    }

    private static AuthoringProblemDraft validDraft(int caseCount) {
        List<JudgeCase> cases = new ArrayList<>();
        for (int index = 1; index <= caseCount; index++) {
            cases.add(new JudgeCase(index + "\n", index + "\n"));
        }
        String text = "这是一道用于测试结构化出题工作流的数组算法题。".repeat(8);
        return new AuthoringProblemDraft(
                "数组求和",
                0,
                text,
                List.of("数组"),
                text,
                "public class Main { public static void main(String[] args) { } }",
                cases,
                AuthoringProblemDraft.JudgeConfig.defaults()
        );
    }

    private static AuthoringSandboxVerifier.SandboxVerification passed() {
        return new AuthoringSandboxVerifier.SandboxVerification(true, List.of());
    }
}

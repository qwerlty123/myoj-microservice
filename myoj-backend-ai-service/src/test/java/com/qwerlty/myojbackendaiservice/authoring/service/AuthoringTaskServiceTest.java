package com.qwerlty.myojbackendaiservice.authoring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.authoring.api.CreateAuthoringTaskRequest;
import com.qwerlty.myojbackendaiservice.authoring.api.ProblemDraftRequirements;
import com.qwerlty.myojbackendaiservice.authoring.api.ReviewAuthoringTaskRequest;
import com.qwerlty.myojbackendaiservice.authoring.graph.QuestionAuthoringGraph;
import com.qwerlty.myojbackendaiservice.authoring.graph.AuthoringDraftValidator;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringProblemDraft;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringReviewDecision;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringStage;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringTask;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringTaskStatus;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringValidation;
import com.qwerlty.myojbackendaiservice.authoring.model.ProblemDraftArtifact;
import com.qwerlty.myojbackendaiservice.authoring.repository.AuthoringTaskRepository;
import com.qwerlty.myojbackendaiservice.authoring.repository.AuthoringTraceRecorder;
import com.qwerlty.myojbackendaiservice.common.ApiException;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.dto.JudgeCase;
import com.qwerlty.myojbackendaiservice.observability.AiMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthoringTaskServiceTest {

    private AuthoringTaskRepository repository;
    private QuestionAuthoringGraph graph;
    private ThreadPoolTaskExecutor executor;
    private AuthoringTaskService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        repository = mock(AuthoringTaskRepository.class);
        graph = mock(QuestionAuthoringGraph.class);
        executor = mock(ThreadPoolTaskExecutor.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new AuthoringTaskService(repository, graph,
                new AuthoringDraftValidator(), mock(AuthoringTraceRecorder.class),
                objectMapper, executor, new AiAgentProperties(),
                new AiMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void repeatedIdempotentCreateReturnsOriginalTaskAndSubmitsItOnlyOnce() {
        AuthoringTask task = task(61, 7, AuthoringTaskStatus.PENDING, null);
        when(repository.create(anyLong(), any(), any(), any(), any(), any())).thenReturn(task);
        CreateAuthoringTaskRequest request = new CreateAuthoringTaskRequest(
                new ProblemDraftRequirements("前缀和", 1, List.of("数组"), List.of(), null));

        assertThat(service.create(7L, request, "same-key").taskId()).isEqualTo("61");
        assertThat(service.create(7L, request, "same-key").taskId()).isEqualTo("61");

        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void hidesTaskExistenceFromAnotherUser() {
        when(repository.findById(61L)).thenReturn(Optional.of(
                task(61, 8, AuthoringTaskStatus.FAILED, null)));

        assertThatThrownBy(() -> service.get(7L, 61L))
                .isInstanceOf(ApiException.class)
                .hasMessage("任务不存在");
    }

    @Test
    void manualRetryCreatesANewAuditedTaskWithSourceId() {
        AuthoringTask source = task(61, 7, AuthoringTaskStatus.FAILED, null);
        AuthoringTask retry = task(62, 7, AuthoringTaskStatus.PENDING, 61L);
        when(repository.findById(61L)).thenReturn(Optional.of(source));
        when(repository.create(anyLong(), any(), any(), any(), any(), any())).thenReturn(retry);

        assertThat(service.retry(7L, 61L).sourceTaskId()).isEqualTo("61");
        verify(repository).create(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(61L), any(), any(), any(), any());
    }

    @Test
    void persistsTheHumanDecisionBeforeSynchronouslyResumingTheGraph() throws Exception {
        AuthoringProblemDraft draft = validDraft(6);
        String draftJson = objectMapper.writeValueAsString(draft);
        String resultJson = objectMapper.writeValueAsString(new ProblemDraftArtifact(
                draft, new AuthoringValidation("java", 6, true, List.of())));
        AuthoringTask awaiting = reviewedTask(71, AuthoringTaskStatus.REVIEW_REQUIRED,
                AuthoringStage.AWAITING_REVIEW, resultJson, null, null, null);
        AuthoringTask submitted = reviewedTask(71, AuthoringTaskStatus.RUNNING,
                AuthoringStage.PUBLISHING, resultJson, "APPROVE", draftJson, null);
        AuthoringTask published = reviewedTask(71, AuthoringTaskStatus.PUBLISHED,
                AuthoringStage.PUBLISHED, resultJson, "APPROVE", draftJson, 9001L);
        when(repository.findById(71L)).thenReturn(
                Optional.of(awaiting), Optional.of(submitted), Optional.of(submitted), Optional.of(published));
        when(repository.submitReview(anyLong(), any(), any(), any(), anyLong(), any())).thenReturn(true);

        var view = service.review(7L, 71L,
                new ReviewAuthoringTaskRequest(AuthoringReviewDecision.APPROVE, null, "  人工复核通过  "));

        assertThat(view.status()).isEqualTo("PUBLISHED");
        assertThat(view.publishedQuestionId()).isEqualTo("9001");
        verify(repository).submitReview(
                org.mockito.ArgumentMatchers.eq(71L),
                org.mockito.ArgumentMatchers.eq("APPROVE"),
                org.mockito.ArgumentMatchers.eq(draftJson),
                any(),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("人工复核通过"));
        verify(graph).resumeReview(submitted);
    }

    @Test
    void rejectsAnInvalidHumanEditBeforePersistingApproval() throws Exception {
        AuthoringProblemDraft valid = validDraft(6);
        AuthoringProblemDraft invalid = validDraft(5);
        String resultJson = objectMapper.writeValueAsString(new ProblemDraftArtifact(
                valid, new AuthoringValidation("java", 6, true, List.of())));
        when(repository.findById(72L)).thenReturn(Optional.of(reviewedTask(
                72, AuthoringTaskStatus.REVIEW_REQUIRED, AuthoringStage.AWAITING_REVIEW,
                resultJson, null, null, null)));

        assertThatThrownBy(() -> service.review(7L, 72L,
                new ReviewAuthoringTaskRequest(AuthoringReviewDecision.APPROVE, invalid, "修改用例")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("人工审核后的草稿不合法");

        verify(repository, never()).submitReview(anyLong(), any(), any(), any(), anyLong(), any());
        verify(graph, never()).resumeReview(any());
    }

    @Test
    void rejectsChangingTheSandboxVerifiedJudgePayload() throws Exception {
        AuthoringProblemDraft verified = validDraft(6);
        List<JudgeCase> changedCases = new ArrayList<>(verified.judgeCase());
        changedCases.set(0, new JudgeCase("1\n", "changed\n"));
        AuthoringProblemDraft reviewed = new AuthoringProblemDraft(
                verified.title(), verified.difficulty(), verified.content(), verified.tags(),
                verified.answer(), verified.referenceCode(), changedCases, verified.judgeConfig());
        String resultJson = objectMapper.writeValueAsString(new ProblemDraftArtifact(
                verified, new AuthoringValidation("java", 6, true, List.of())));
        when(repository.findById(73L)).thenReturn(Optional.of(reviewedTask(
                73, AuthoringTaskStatus.REVIEW_REQUIRED, AuthoringStage.AWAITING_REVIEW,
                resultJson, null, null, null)));

        assertThatThrownBy(() -> service.review(7L, 73L,
                new ReviewAuthoringTaskRequest(AuthoringReviewDecision.APPROVE, reviewed, "修改用例")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已经通过沙箱");

        verify(repository, never()).submitReview(anyLong(), any(), any(), any(), anyLong(), any());
        verify(graph, never()).resumeReview(any());
    }

    private static AuthoringTask task(long id, long userId, AuthoringTaskStatus status, Long sourceTaskId) {
        LocalDateTime now = LocalDateTime.now();
        return new AuthoringTask(id, userId, sourceTaskId, "idem-" + id, "PROBLEM_DRAFT",
                "{\"topic\":\"prefix sum\"}", null, status, AuthoringStage.QUEUED,
                0, 0, false, null, null, null, "authoring-v1", "authoring-v1",
                null, null, now, now);
    }

    private static AuthoringTask reviewedTask(long id,
                                               AuthoringTaskStatus status,
                                               AuthoringStage stage,
                                               String resultJson,
                                               String decision,
                                               String draftJson,
                                               Long questionId) {
        LocalDateTime now = LocalDateTime.now();
        return new AuthoringTask(
                id, 7L, null, "idem-" + id, "PROBLEM_DRAFT",
                "{\"topic\":\"prefix sum\"}", resultJson, status, stage,
                status == AuthoringTaskStatus.PUBLISHED ? 100 : 95,
                0, false, null, null, "test-model", "authoring-v1", "authoring-v2-hitl",
                decision, draftJson, decision == null ? null : 7L,
                decision == null ? null : "人工复核通过", questionId,
                decision == null ? null : now, now,
                status == AuthoringTaskStatus.PUBLISHED ? now : null, now, now
        );
    }

    private static AuthoringProblemDraft validDraft(int caseCount) {
        List<JudgeCase> cases = new ArrayList<>();
        for (int index = 1; index <= caseCount; index++) {
            cases.add(new JudgeCase(index + "\n", index + "\n"));
        }
        String text = "这是一道用于测试人工审核发布工作流的数组算法题。".repeat(8);
        return new AuthoringProblemDraft(
                "数组求和", 1, text, List.of("数组"), text,
                "public class Main { public static void main(String[] args) { } }",
                cases, AuthoringProblemDraft.JudgeConfig.defaults());
    }
}

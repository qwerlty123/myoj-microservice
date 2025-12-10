package com.qwerlty.myojbackendaiservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.qwerlty.myojbackendaiservice.exception.BusinessException;
import com.qwerlty.myojbackendaiservice.client.QuestionServiceClient;
import com.qwerlty.myojbackendaiservice.generation.workflow.AuthoringWorkflowRegistry;
import com.qwerlty.myojbackendaiservice.generation.workflow.ToolExecutionGuard;
import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.manager.GenerationAdmissionControl;
import com.qwerlty.myojbackendaiservice.manager.GenerationExecutionRegistry;
import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemDraft;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GenerationValidationReport;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftArtifact;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftRequirements;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftTaskRequest;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStatus;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationLane;
import com.qwerlty.myojbackendaiservice.queue.GenerationStreamManager;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxConfigurationException;
import com.qwerlty.myojbackendaiservice.service.impl.GenerationTaskServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.Date;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTaskServiceImplTest {

    private AiProblemGenerationTaskMapper mapper;
    private GenerationAdmissionControl admission;
    private GenerationStreamManager stream;
    private AuthoringWorkflowRegistry registry;
    private ExecutorService executor;
    private ObjectMapper objectMapper;
    private GenerationTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(AiProblemGenerationTaskMapper.class);
        admission = mock(GenerationAdmissionControl.class);
        stream = mock(GenerationStreamManager.class);
        registry = mock(AuthoringWorkflowRegistry.class);
        doReturn(ProblemDraftTaskRequest.class).when(registry).requestType(AuthoringTaskType.PROBLEM_DRAFT);
        when(admission.reserve(anyLong(), anyString(), eq(AuthoringTaskType.PROBLEM_DRAFT), anyString()))
                .thenAnswer(invocation -> new GenerationAdmissionControl.Reservation(
                        invocation.getArgument(3), GenerationLane.PUBLIC_AUTHORING, 3, LocalDate.now()));
        when(admission.settle(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        executor = Executors.newSingleThreadExecutor();
        objectMapper = new ObjectMapper();
        service = new GenerationTaskServiceImpl(mapper, admission, stream,
                mock(QuestionServiceClient.class), mock(ToolExecutionGuard.class),
                new GenerationExecutionRegistry(), registry,
                objectMapper, executor, executor, new SimpleMeterRegistry(), "test-model",
                "generation-v1", 3, 5_000L, 5_000L, 5_000L, "internal-test-token");
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void idempotentRequestReturnsExistingTaskWithoutConsumingAnotherQuota() {
        AiProblemGenerationTask existing = task(101L, 7L, GenerationStatus.PENDING);
        when(mapper.selectByRequestKey(anyString())).thenReturn(existing);

        assertThat(service.create(AuthoringTaskType.PROBLEM_DRAFT, request(), 7L,
                UUID.randomUUID().toString()).getTaskId()).isEqualTo(101L);

        verify(admission, never()).reserve(anyLong(), anyString(), any(), anyString());
        verify(mapper, never()).insert(any(AiProblemGenerationTask.class));
        verify(stream).enqueue(101L, GenerationLane.PUBLIC_AUTHORING);
    }

    @Test
    void persistsPendingBeforeEnqueueAndLeavesItRecoverableWhenRedisIsDown() {
        when(mapper.insert(any(AiProblemGenerationTask.class))).thenAnswer(invocation -> {
            AiProblemGenerationTask inserted = invocation.getArgument(0);
            inserted.setId(102L);
            return 1;
        });
        when(stream.enqueue(102L, GenerationLane.PUBLIC_AUTHORING))
                .thenThrow(new IllegalStateException("redis unavailable"));

        var result = service.create(AuthoringTaskType.PROBLEM_DRAFT, request(), 7L,
                UUID.randomUUID().toString());

        assertThat(result.getTaskId()).isEqualTo(102L);
        assertThat(result.getStatus()).isEqualTo("PENDING");
        verify(mapper).insert(any(AiProblemGenerationTask.class));
        verify(stream).enqueue(102L, GenerationLane.PUBLIC_AUTHORING);
    }

    @Test
    void rejectsMalformedIdempotencyKeyBeforeRateLimitOrPersistence() {
        assertThatThrownBy(() -> service.create(
                AuthoringTaskType.PROBLEM_DRAFT, request(), 7L, "not-a-uuid"))
                .isInstanceOf(BusinessException.class);

        verify(admission, never()).reserve(anyLong(), anyString(), any(), anyString());
        verify(mapper, never()).insert(any(AiProblemGenerationTask.class));
    }

    @Test
    void completedEngineArtifactTransitionsOnlyToReviewRequired() throws Exception {
        AiProblemGenerationTask running = task(103L, 7L, GenerationStatus.RUNNING);
        running.setRequestJson(objectMapper.writeValueAsString(request()));
        when(mapper.selectById(103L)).thenReturn(running);
        when(registry.execute(eq(AuthoringTaskType.PROBLEM_DRAFT), any(), any())).thenReturn(
                new ProblemDraftArtifact(new GeneratedProblemDraft(),
                        new GenerationValidationReport(), java.util.List.of(), false));
        when(mapper.markReviewRequired(anyLong(), anyString(), anyLong())).thenReturn(1);

        service.execute(103L);

        verify(mapper).markReviewRequired(anyLong(), anyString(), anyLong());
        verify(mapper, never()).markTerminal(anyLong(),
                org.mockito.ArgumentMatchers.eq(GenerationStatus.FAILED.getValue()),
                any(), any(), any(), anyLong());
    }

    @Test
    void transientModelFailureSchedulesDelayedRetryWithoutHotLoop() throws Exception {
        AiProblemGenerationTask running = task(104L, 7L, GenerationStatus.RUNNING);
        running.setAttemptCount(1);
        running.setRequestJson(objectMapper.writeValueAsString(request()));
        when(mapper.selectById(104L)).thenReturn(running);
        when(registry.execute(eq(AuthoringTaskType.PROBLEM_DRAFT), any(), any()))
                .thenThrow(new TransientAiException("temporary"));
        when(mapper.markRetryDelayed(eq(104L), eq("MODEL_UNAVAILABLE"),
                eq("模型服务暂时不可用"), eq("GENERATING_SPEC"), any(Date.class))).thenReturn(1);

        service.execute(104L);

        verify(mapper).markRetryDelayed(eq(104L), eq("MODEL_UNAVAILABLE"),
                eq("模型服务暂时不可用"), eq("GENERATING_SPEC"), any(Date.class));
        verify(admission).revertStart(running);
        verify(stream, never()).enqueue(eq(104L), any(GenerationLane.class));
    }

    @Test
    void distributedPermitContentionDefersWithoutConsumingAnAttempt() throws Exception {
        AiProblemGenerationTask running = task(111L, 7L, GenerationStatus.RUNNING);
        running.setAttemptCount(2);
        running.setRequestJson(objectMapper.writeValueAsString(request()));
        when(mapper.selectById(111L)).thenReturn(running);
        when(registry.execute(eq(AuthoringTaskType.PROBLEM_DRAFT), any(), any()))
                .thenThrow(new RejectedExecutionException("model permit busy"));
        when(mapper.markCapacityDeferred(eq(111L), eq("GENERATING_SPEC"), any(Date.class)))
                .thenReturn(1);

        service.execute(111L);

        verify(mapper).markCapacityDeferred(eq(111L), eq("GENERATING_SPEC"), any(Date.class));
        verify(admission).revertStart(running);
        verify(mapper, never()).markTerminal(eq(111L), anyInt(), any(), any(), any(), anyLong());
    }

    @Test
    void failedQualityGateIsTerminalAndIsNotRetried() throws Exception {
        AiProblemGenerationTask running = task(105L, 7L, GenerationStatus.RUNNING);
        running.setAttemptCount(1);
        running.setRequestJson(objectMapper.writeValueAsString(request()));
        when(mapper.selectById(105L)).thenReturn(running);
        when(registry.execute(eq(AuthoringTaskType.PROBLEM_DRAFT), any(), any()))
                .thenThrow(new GenerationValidationException("三语言输出不一致"));
        service.execute(105L);

        verify(mapper, never()).markRetryDelayed(anyLong(), anyString(), anyString(), anyString(), any(Date.class));
        verify(mapper).markTerminal(eq(105L), eq(GenerationStatus.FAILED.getValue()),
                eq("QUALITY_GATE_FAILED"), eq("三语言输出不一致"), eq("GENERATING_SPEC"), anyLong());
        verify(admission).settle(running, false);
    }

    @Test
    void truncatedToolArgumentsUseTheRemainingTaskAttempt() throws Exception {
        AiProblemGenerationTask running = task(110L, 7L, GenerationStatus.RUNNING);
        running.setAttemptCount(2);
        running.setRequestJson(objectMapper.writeValueAsString(request()));
        when(mapper.selectById(110L)).thenReturn(running);
        ToolExecutionException truncatedArguments = new ToolExecutionException(
                mock(ToolDefinition.class),
                new JsonEOFException(null, null, "Unexpected end-of-input"));
        when(registry.execute(eq(AuthoringTaskType.PROBLEM_DRAFT), any(), any()))
                .thenThrow(truncatedArguments);
        when(mapper.markRetryDelayed(eq(110L), eq("MODEL_OUTPUT_INVALID"),
                eq("模型工具参数不完整，已安排重试"), eq("GENERATING_SPEC"), any(Date.class)))
                .thenReturn(1);

        service.execute(110L);

        verify(mapper).markRetryDelayed(eq(110L), eq("MODEL_OUTPUT_INVALID"),
                eq("模型工具参数不完整，已安排重试"), eq("GENERATING_SPEC"), any(Date.class));
        verify(stream, never()).enqueue(eq(110L), any(GenerationLane.class));
        verify(mapper, never()).markTerminal(eq(110L), eq(GenerationStatus.FAILED.getValue()),
                any(), any(), any(), anyLong());
    }

    @Test
    void permanentSandboxMisconfigurationFailsWithoutRegeneratingTheProblem() throws Exception {
        AiProblemGenerationTask running = task(106L, 7L, GenerationStatus.RUNNING);
        running.setAttemptCount(1);
        running.setRequestJson(objectMapper.writeValueAsString(request()));
        when(mapper.selectById(106L)).thenReturn(running);
        when(registry.execute(eq(AuthoringTaskType.PROBLEM_DRAFT), any(), any()))
                .thenThrow(new SandboxConfigurationException("沙箱运行时缺少编译命令: go"));

        service.execute(106L);

        verify(mapper, never()).markRetry(anyLong(), anyString(), anyString());
        verify(mapper).markTerminal(eq(106L), eq(GenerationStatus.FAILED.getValue()),
                eq("SANDBOX_MISCONFIGURED"), eq("代码沙箱运行环境配置错误"),
                eq("GENERATING_SPEC"), anyLong());
        verify(stream, never()).enqueue(106L);
    }

    @Test
    void manualRetryRequiresCreatingANewBillableTask() {
        AiProblemGenerationTask failed = task(107L, 7L, GenerationStatus.FAILED);
        assertThatThrownBy(() -> service.retry(107L, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重新创建任务");
        verify(mapper, never()).resetForRetry(anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void recoveredTaskDiscardsCheckpointWhenDeployedPromptChanged() throws Exception {
        AiProblemGenerationTask running = task(109L, 7L, GenerationStatus.RUNNING);
        running.setPromptVersion("generation-v0");
        running.setWorkflowStateJson("{\"schemaVersion\":1}");
        running.setRequestJson(objectMapper.writeValueAsString(request()));
        when(mapper.selectById(109L)).thenReturn(running);
        when(mapper.replacePromptVersionAndClearCheckpoint(109L, "generation-v1")).thenReturn(1);
        when(registry.execute(eq(AuthoringTaskType.PROBLEM_DRAFT), any(), any())).thenReturn(
                new ProblemDraftArtifact(new GeneratedProblemDraft(),
                        new GenerationValidationReport(), java.util.List.of(), false));
        when(mapper.markReviewRequired(anyLong(), anyString(), anyLong())).thenReturn(1);

        service.execute(109L);

        verify(mapper).replacePromptVersionAndClearCheckpoint(109L, "generation-v1");
        verify(mapper).markReviewRequired(eq(109L), anyString(), anyLong());
    }

    private ProblemDraftTaskRequest request() {
        ProblemDraftRequirements requirements = new ProblemDraftRequirements();
        requirements.setTopic("sliding window");
        ProblemDraftTaskRequest request = new ProblemDraftTaskRequest();
        request.setRequirements(requirements);
        return request;
    }

    private AiProblemGenerationTask task(Long id, Long userId, GenerationStatus status) {
        AiProblemGenerationTask task = new AiProblemGenerationTask();
        task.setId(id);
        task.setUserId(userId);
        task.setRequestKey("request-" + id);
        task.setMode("PROBLEM_DRAFT");
        task.setLane(GenerationLane.PUBLIC_AUTHORING.name());
        task.setTraceId("trace-" + id);
        task.setQuotaDate(java.sql.Date.valueOf(LocalDate.now()));
        task.setQuotaStatus("RESERVED");
        task.setPromptVersion("generation-v1");
        task.setStatus(status.getValue());
        task.setStage(status == GenerationStatus.PENDING ? "QUEUED" : "GENERATING_SPEC");
        task.setProgress(status == GenerationStatus.PENDING ? 0 : 10);
        task.setAttemptCount(0);
        task.setCancelRequested(0);
        task.setCreateTime(new Date());
        task.setUpdateTime(new Date());
        return task;
    }
}

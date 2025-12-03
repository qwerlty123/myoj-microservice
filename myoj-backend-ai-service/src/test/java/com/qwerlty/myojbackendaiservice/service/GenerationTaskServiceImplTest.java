package com.qwerlty.myojbackendaiservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.qwerlty.myojbackendaiservice.exception.BusinessException;
import com.qwerlty.myojbackendaiservice.generation.workflow.AuthoringWorkflowRegistry;
import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.manager.GenerationRateLimiter;
import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemDraft;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GenerationValidationReport;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftArtifact;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftRequirements;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftTaskRequest;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStatus;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTaskServiceImplTest {

    private AiProblemGenerationTaskMapper mapper;
    private GenerationRateLimiter limiter;
    private GenerationStreamManager stream;
    private AuthoringWorkflowRegistry registry;
    private ExecutorService executor;
    private ObjectMapper objectMapper;
    private GenerationTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(AiProblemGenerationTaskMapper.class);
        limiter = mock(GenerationRateLimiter.class);
        stream = mock(GenerationStreamManager.class);
        registry = mock(AuthoringWorkflowRegistry.class);
        doReturn(ProblemDraftTaskRequest.class).when(registry).requestType(AuthoringTaskType.PROBLEM_DRAFT);
        executor = Executors.newSingleThreadExecutor();
        objectMapper = new ObjectMapper();
        service = new GenerationTaskServiceImpl(mapper, limiter, stream, registry,
                objectMapper, executor, new SimpleMeterRegistry(), "test-model", "generation-v1", 3,
                5_000L, 5_000L, 5_000L);
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

        verify(limiter, never()).tryAcquire(anyLong());
        verify(mapper, never()).insert(any(AiProblemGenerationTask.class));
        verify(stream).enqueue(101L);
    }

    @Test
    void persistsPendingBeforeEnqueueAndLeavesItRecoverableWhenRedisIsDown() {
        when(limiter.tryAcquire(7L)).thenReturn(true);
        when(mapper.insert(any(AiProblemGenerationTask.class))).thenAnswer(invocation -> {
            AiProblemGenerationTask inserted = invocation.getArgument(0);
            inserted.setId(102L);
            return 1;
        });
        when(stream.enqueue(102L)).thenThrow(new IllegalStateException("redis unavailable"));

        var result = service.create(AuthoringTaskType.PROBLEM_DRAFT, request(), 7L,
                UUID.randomUUID().toString());

        assertThat(result.getTaskId()).isEqualTo(102L);
        assertThat(result.getStatus()).isEqualTo("PENDING");
        verify(mapper).insert(any(AiProblemGenerationTask.class));
        verify(stream).enqueue(102L);
    }

    @Test
    void rejectsMalformedIdempotencyKeyBeforeRateLimitOrPersistence() {
        assertThatThrownBy(() -> service.create(
                AuthoringTaskType.PROBLEM_DRAFT, request(), 7L, "not-a-uuid"))
                .isInstanceOf(BusinessException.class);

        verify(limiter, never()).tryAcquire(anyLong());
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
                any(), any(), anyLong());
    }

    @Test
    void transientModelFailureReturnsTaskToPendingAndRequeues() throws Exception {
        AiProblemGenerationTask running = task(104L, 7L, GenerationStatus.RUNNING);
        running.setAttemptCount(1);
        running.setRequestJson(objectMapper.writeValueAsString(request()));
        when(mapper.selectById(104L)).thenReturn(running);
        when(registry.execute(eq(AuthoringTaskType.PROBLEM_DRAFT), any(), any()))
                .thenThrow(new TransientAiException("temporary"));
        when(mapper.markRetry(104L, "MODEL_UNAVAILABLE", "模型服务暂时不可用")).thenReturn(1);

        service.execute(104L);

        verify(mapper).markRetry(104L, "MODEL_UNAVAILABLE", "模型服务暂时不可用");
        verify(stream).enqueue(104L);
    }

    @Test
    void failedQualityGateRegeneratesWithinTheBoundedAttemptBudget() throws Exception {
        AiProblemGenerationTask running = task(105L, 7L, GenerationStatus.RUNNING);
        running.setAttemptCount(1);
        running.setRequestJson(objectMapper.writeValueAsString(request()));
        when(mapper.selectById(105L)).thenReturn(running);
        when(registry.execute(eq(AuthoringTaskType.PROBLEM_DRAFT), any(), any()))
                .thenThrow(new GenerationValidationException("三语言输出不一致"));
        when(mapper.markRetry(105L, "QUALITY_GATE_FAILED", "三语言输出不一致")).thenReturn(1);

        service.execute(105L);

        verify(mapper).markRetry(105L, "QUALITY_GATE_FAILED", "三语言输出不一致");
        verify(stream).enqueue(105L);
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
        when(mapper.markRetry(110L, "MODEL_OUTPUT_INVALID", "模型工具参数不完整，已安排重试"))
                .thenReturn(1);

        service.execute(110L);

        verify(mapper).markRetry(110L, "MODEL_OUTPUT_INVALID", "模型工具参数不完整，已安排重试");
        verify(stream).enqueue(110L);
        verify(mapper, never()).markTerminal(eq(110L), eq(GenerationStatus.FAILED.getValue()),
                any(), any(), anyLong());
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
                eq("SANDBOX_MISCONFIGURED"), eq("代码沙箱运行环境配置错误"), anyLong());
        verify(stream, never()).enqueue(106L);
    }

    @Test
    void manualRetryPreservesCompatibleCheckpoint() {
        AiProblemGenerationTask failed = task(107L, 7L, GenerationStatus.FAILED);
        when(mapper.selectById(107L)).thenReturn(failed);
        when(limiter.tryAcquire(7L)).thenReturn(true);
        when(mapper.resetForRetry(107L, 7L, "generation-v1", 0)).thenReturn(1);

        service.retry(107L, 7L);

        verify(mapper).resetForRetry(107L, 7L, "generation-v1", 0);
        verify(stream).enqueue(107L);
    }

    @Test
    void manualRetryDiscardsCheckpointWhenPromptVersionChanged() {
        AiProblemGenerationTask failed = task(108L, 7L, GenerationStatus.FAILED);
        failed.setPromptVersion("generation-v0");
        when(mapper.selectById(108L)).thenReturn(failed);
        when(limiter.tryAcquire(7L)).thenReturn(true);
        when(mapper.resetForRetry(108L, 7L, "generation-v1", 1)).thenReturn(1);

        service.retry(108L, 7L);

        verify(mapper).resetForRetry(108L, 7L, "generation-v1", 1);
        verify(stream).enqueue(108L);
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
        task.setMode("PROBLEM_DRAFT");
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

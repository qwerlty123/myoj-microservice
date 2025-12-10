package com.qwerlty.myojbackendaiservice.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.qwerlty.myojbackendaiservice.common.ErrorCode;
import com.qwerlty.myojbackendaiservice.exception.BusinessException;
import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.generation.AiCallContext;
import com.qwerlty.myojbackendaiservice.generation.checkpoint.DatabaseWorkflowCheckpointStore;
import com.qwerlty.myojbackendaiservice.generation.workflow.AuthoringArtifact;
import com.qwerlty.myojbackendaiservice.generation.workflow.AuthoringRequest;
import com.qwerlty.myojbackendaiservice.generation.workflow.AuthoringWorkflowRegistry;
import com.qwerlty.myojbackendaiservice.generation.workflow.WorkflowContext;
import com.qwerlty.myojbackendaiservice.generation.workflow.ExecutionFingerprint;
import com.qwerlty.myojbackendaiservice.generation.workflow.ToolExecutionGuard;
import com.qwerlty.myojbackendaiservice.client.QuestionServiceClient;
import com.qwerlty.myojbackendaiservice.manager.GenerationAdmissionControl;
import com.qwerlty.myojbackendaiservice.manager.GenerationExecutionRegistry;
import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.model.dto.generation.AuthoringTaskResult;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftTaskRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityReviewTaskRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.TestCaseTaskRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.TestCaseArtifact;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemSourceDraft;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedJudgeCase;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ArtifactValidationRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ArtifactValidationResult;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityReviewArtifact;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStage;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationStatus;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationLane;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationTaskPageVO;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationTaskVO;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationQuotaVO;
import com.qwerlty.myojbackendaiservice.queue.GenerationStreamManager;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxConfigurationException;
import com.qwerlty.myojbackendaiservice.service.GenerationTaskService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class GenerationTaskServiceImpl implements GenerationTaskService {

    private final AiProblemGenerationTaskMapper taskMapper;
    private final GenerationAdmissionControl admissionControl;
    private final GenerationStreamManager streamManager;
    private final QuestionServiceClient questionServiceClient;
    private final ToolExecutionGuard toolExecutionGuard;
    private final GenerationExecutionRegistry executionRegistry;
    private final AuthoringWorkflowRegistry workflowRegistry;
    private final ObjectMapper objectMapper;
    private final ExecutorService publicExecutor;
    private final ExecutorService reviewExecutor;
    private final MeterRegistry meterRegistry;
    private final String modelName;
    private final String promptVersion;
    private final int maxAttempts;
    private final long problemDraftTimeoutMs;
    private final long testCasesTimeoutMs;
    private final long qualityReviewTimeoutMs;
    private final String internalToken;

    public GenerationTaskServiceImpl(
            AiProblemGenerationTaskMapper taskMapper,
            GenerationAdmissionControl admissionControl,
            GenerationStreamManager streamManager,
            QuestionServiceClient questionServiceClient,
            ToolExecutionGuard toolExecutionGuard,
            GenerationExecutionRegistry executionRegistry,
            AuthoringWorkflowRegistry workflowRegistry,
            ObjectMapper objectMapper,
            @Qualifier("problemGenerationPublicExecutor") ExecutorService publicExecutor,
            @Qualifier("problemGenerationReviewExecutor") ExecutorService reviewExecutor,
            MeterRegistry meterRegistry,
            @Value("${myoj.ai.model-name}") String modelName,
            @Value("${myoj.ai.generation.prompt-version:v1}") String promptVersion,
            @Value("${myoj.ai.generation.max-attempts:3}") int maxAttempts,
            @Value("${myoj.ai.generation.workflow.problem-draft-timeout-ms:720000}") long problemDraftTimeoutMs,
            @Value("${myoj.ai.generation.workflow.test-cases-timeout-ms:1080000}") long testCasesTimeoutMs,
            @Value("${myoj.ai.generation.workflow.quality-review-timeout-ms:900000}") long qualityReviewTimeoutMs,
            @Value("${myoj.ai.internal-token}") String internalToken) {
        this.taskMapper = taskMapper;
        this.admissionControl = admissionControl;
        this.streamManager = streamManager;
        this.questionServiceClient = questionServiceClient;
        this.toolExecutionGuard = toolExecutionGuard;
        this.executionRegistry = executionRegistry;
        this.workflowRegistry = workflowRegistry;
        this.objectMapper = objectMapper;
        this.publicExecutor = publicExecutor;
        this.reviewExecutor = reviewExecutor;
        this.meterRegistry = meterRegistry;
        this.modelName = modelName;
        this.promptVersion = promptVersion;
        this.maxAttempts = maxAttempts;
        this.problemDraftTimeoutMs = problemDraftTimeoutMs;
        this.testCasesTimeoutMs = testCasesTimeoutMs;
        this.qualityReviewTimeoutMs = qualityReviewTimeoutMs;
        this.internalToken = internalToken;
    }

    @Override
    public GenerationTaskVO create(AuthoringTaskType type,
                                   AuthoringRequest request,
                                   Long userId,
                                   String role,
                                   String idempotencyKey) {
        validateCreate(type, request, userId, idempotencyKey);
        String requestKey = sha256(userId + ":" + idempotencyKey);
        AiProblemGenerationTask existing = taskMapper.selectByRequestKey(requestKey);
        if (existing != null) {
            log.info("[AI_GENERATION] idempotent task reused taskId={} userId={} status={} stage={}",
                    existing.getId(), userId, existing.getStatus(), existing.getStage());
            if (GenerationStatus.PENDING.getValue() == existing.getStatus()) {
                enqueueSafely(existing);
            }
            return toVO(existing);
        }
        // Idempotent retries must not depend on mutable downstream state. Only a genuinely new
        // quality-review task fetches the authoritative submission snapshot.
        prepareAuthoritativeRequest(type, request, userId);
        log.info("[AI_GENERATION] create validated userId={} type={} model={} promptVersion={}",
                userId, type, modelName, promptVersion);
        GenerationAdmissionControl.Reservation reservation =
                admissionControl.reserve(userId, role, type, requestKey);

        Date now = new Date();
        AiProblemGenerationTask task = new AiProblemGenerationTask();
        task.setRequestKey(requestKey);
        task.setUserId(userId);
        task.setMode(type.name());
        task.setLane(reservation.lane().name());
        if (request instanceof TestCaseTaskRequest testCaseRequest) {
            task.setSourceTaskId(testCaseRequest.getSourceTaskId());
        } else if (request instanceof QualityReviewTaskRequest qualityRequest) {
            task.setSubmissionId(qualityRequest.getSubmissionId());
        }
        task.setTraceId(UUID.randomUUID().toString());
        task.setStatus(GenerationStatus.PENDING.getValue());
        task.setStage(GenerationStage.QUEUED.name());
        task.setProgress(0);
        task.setRequestJson(writeJson(request));
        task.setModelName(modelName);
        task.setPromptVersion(promptVersion);
        task.setInputTokens(0);
        task.setOutputTokens(0);
        task.setQuotaCost(reservation.cost());
        task.setQuotaDate(java.sql.Date.valueOf(reservation.quotaDate()));
        task.setQuotaStatus("RESERVED");
        task.setEstimatedCostMicros(0L);
        task.setModelCallCount(0);
        task.setLatencyMs(0L);
        task.setAttemptCount(0);
        task.setCancelRequested(0);
        task.setDegraded(0);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        task.setVersion(0);
        try {
            taskMapper.insert(task);
            log.info("[AI_GENERATION] task persisted taskId={} userId={} status=PENDING stage=QUEUED",
                    task.getId(), userId);
        } catch (DuplicateKeyException exception) {
            admissionControl.rollback(userId, requestKey, reservation.lane(), reservation.quotaDate());
            AiProblemGenerationTask duplicate = taskMapper.selectByRequestKey(requestKey);
            log.info("[AI_GENERATION] concurrent duplicate task reused taskId={} userId={}",
                    duplicate == null ? null : duplicate.getId(), userId);
            return toVO(duplicate);
        } catch (RuntimeException exception) {
            admissionControl.rollback(userId, requestKey, reservation.lane(), reservation.quotaDate());
            log.error("[AI_GENERATION] task persistence failed userId={} mode={} errorType={}",
                    userId, type, exception.getClass().getSimpleName(), exception);
            throw exception;
        }
        taskCounter(type, "created").increment();
        enqueueSafely(task);
        return toVO(task);
    }

    @Override
    public GenerationTaskVO get(Long taskId, Long userId, String role) {
        AiProblemGenerationTask task = taskMapper.selectById(requirePositive(taskId));
        requirePositive(userId);
        return toVO("admin".equals(role) ? requireTask(task) : owned(task, userId));
    }

    @Override
    public GenerationTaskPageVO history(Long userId, String role, int current, int pageSize, AuthoringTaskType type) {
        requirePositive(userId);
        if (current <= 0 || pageSize <= 0 || pageSize > 50) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "分页参数不合法");
        }
        String storedType = type == null ? null : type.name();
        long offset = (long) (current - 1) * pageSize;
        boolean admin = "admin".equals(role);
        long total = admin ? taskMapper.countAllHistoryByType(storedType)
                : taskMapper.countHistoryByType(userId, storedType);
        List<AiProblemGenerationTask> tasks = admin
                ? taskMapper.listAllHistoryByType(storedType, offset, pageSize)
                : taskMapper.listHistoryByType(userId, storedType, offset, pageSize);
        List<GenerationTaskVO> records = tasks
                .stream().map(this::toVO).toList();
        return new GenerationTaskPageVO(records, total, current, pageSize);
    }

    @Override
    public GenerationTaskVO retry(Long taskId, Long userId) {
        throw new BusinessException(ErrorCode.OPERATION_ERROR, "请重新创建任务；新任务会独立计费并保留原失败记录");
    }

    @Override
    public GenerationTaskVO cancel(Long taskId, Long userId) {
        taskId = requirePositive(taskId);
        userId = requirePositive(userId);
        AiProblemGenerationTask task = owned(taskMapper.selectById(taskId), userId);
        GenerationStatus status = GenerationStatus.fromValue(task.getStatus());
        if (status == GenerationStatus.PENDING) {
            if (taskMapper.cancelPending(taskId, userId) <= 0) {
                AiProblemGenerationTask current = owned(taskMapper.selectById(taskId), userId);
                if (GenerationStatus.fromValue(current.getStatus()) == GenerationStatus.RUNNING) {
                    taskMapper.requestRunningCancellation(taskId, userId);
                } else {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "任务状态已变化");
                }
            } else {
                settle(task, true);
            }
        } else if (status == GenerationStatus.RUNNING) {
            taskMapper.requestRunningCancellation(taskId, userId);
        } else {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "当前任务不能取消");
        }
        log.info("[AI_GENERATION] cancellation recorded taskId={} userId={} previousStatus={}",
                taskId, userId, status);
        return toVO(taskMapper.selectById(taskId));
    }

    @Override
    public void execute(Long taskId) {
        AiProblemGenerationTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("[AI_GENERATION] execution skipped because task is missing taskId={}", taskId);
            return;
        }
        if (task.getStatus() != GenerationStatus.RUNNING.getValue()) {
            log.info("[AI_GENERATION] execution skipped because task is not running taskId={} status={} stage={}",
                    taskId, task.getStatus(), task.getStage());
            return;
        }
        long started = System.nanoTime();
        Future<AuthoringArtifact> future = null;
        AuthoringTaskType taskType = AuthoringTaskType.parse(task.getMode());
        long taskTimeoutMs = timeoutMs(taskType);
        if (!Objects.equals(promptVersion, task.getPromptVersion())) {
            if (taskMapper.replacePromptVersionAndClearCheckpoint(taskId, promptVersion) <= 0) {
                log.warn("[AI_GENERATION] execution skipped because prompt upgrade lost task ownership taskId={}",
                        taskId);
                return;
            }
            task.setPromptVersion(promptVersion);
            task.setWorkflowStateJson(null);
            meterRegistry.counter("ai_authoring_checkpoints_total",
                    "type", taskType.name(), "operation", "discard_prompt_upgrade").increment();
        }
        log.info("[AI_GENERATION] execution started taskId={} type={} attempt={} timeoutMs={}",
                taskId, taskType, task.getAttemptCount(), taskTimeoutMs);
        executionRegistry.register(task);
        try {
            AuthoringRequest request = readRequest(taskType, task.getRequestJson());
            WorkflowContext workflowContext = new WorkflowContext(taskId, task.getUserId(),
                    taskType == AuthoringTaskType.QUALITY_REVIEW ? "admin" : "user",
                    task.getTraceId(), task.getSubmissionId(), taskType, task.getPromptVersion(),
                    taskTimeoutMs, objectMapper, stage -> {
                int updated = taskMapper.updateStage(taskId, stage.name(), stage.getProgress());
                log.info("[AI_GENERATION] stage updated taskId={} stage={} progress={} databaseUpdated={}",
                        taskId, stage.name(), stage.getProgress(), updated > 0);
            }, new DatabaseWorkflowCheckpointStore(taskId, taskType, taskMapper, objectMapper, meterRegistry),
                    () -> cancellationRequested(taskId), meterRegistry, toolExecutionGuard);
            ExecutorService executor = taskType == AuthoringTaskType.QUALITY_REVIEW
                    ? reviewExecutor : publicExecutor;
            GenerationLane executionLane = task.getLane() == null
                    ? GenerationLane.forType(taskType) : GenerationLane.valueOf(task.getLane());
            long deadline = System.currentTimeMillis() + taskTimeoutMs;
            future = executor.submit(() -> {
                AiCallContext.bind(taskId, executionLane, deadline);
                try {
                    return workflowRegistry.execute(taskType, workflowContext, request);
                } finally {
                    AiCallContext.clear();
                }
            });
            log.info("[AI_GENERATION] worker submitted taskId={}", taskId);
            AuthoringArtifact artifact = future.get(taskTimeoutMs, TimeUnit.MILLISECONDS);
            long latency = elapsedMillis(started);
            log.info("[AI_GENERATION] generation engine returned taskId={} latencyMs={}", taskId, latency);
            AiProblemGenerationTask current = taskMapper.selectById(taskId);
            if (current != null && Integer.valueOf(1).equals(current.getCancelRequested())) {
                taskMapper.markTerminal(taskId, GenerationStatus.CANCELLED.getValue(),
                        null, null, null, latency);
                settle(task, false);
                taskCounter(taskType, "cancelled").increment();
                log.info("[AI_GENERATION] task cancelled after worker returned taskId={} latencyMs={}",
                        taskId, latency);
                return;
            }
            int updated = taskMapper.markReviewRequired(taskId,
                    writeJson(AuthoringTaskResult.of(taskType, artifact)), latency);
            if (updated > 0) {
                if (artifact instanceof QualityReviewArtifact qualityArtifact
                        && qualityArtifact.getReport() != null
                        && !qualityArtifact.getReport().isComplete()) {
                    taskMapper.markDegraded(taskId);
                }
                settle(task, false);
                taskCounter(taskType, "review_required").increment();
                meterRegistry.timer("ai_generation_duration", "type", taskType.name())
                        .record(latency, TimeUnit.MILLISECONDS);
                log.info("[AI_GENERATION] task ready for review taskId={} status=REVIEW_REQUIRED latencyMs={}",
                        taskId, latency);
            } else {
                log.warn("[AI_GENERATION] review result was not persisted because task state changed taskId={}",
                        taskId);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("[AI_GENERATION] execution interrupted taskId={}", taskId);
            fail(task, exception, elapsedMillis(started));
        } catch (TimeoutException exception) {
            long latency = elapsedMillis(started);
            log.error("[AI_GENERATION] execution timed out taskId={} timeoutMs={} latencyMs={}",
                    taskId, taskTimeoutMs, latency);
            AiProblemGenerationTask current = taskMapper.selectById(taskId);
            if (current != null && Integer.valueOf(1).equals(current.getCancelRequested())) {
                taskMapper.markTerminal(taskId, GenerationStatus.CANCELLED.getValue(),
                        null, null, null, latency);
                settle(task, false);
                taskCounter(taskType, "cancelled").increment();
                log.info("[AI_GENERATION] timed-out task marked cancelled taskId={}", taskId);
            } else {
                taskMapper.markTerminal(taskId, GenerationStatus.TIMED_OUT.getValue(),
                        "TASK_TIMEOUT", "AI 出题任务执行超时",
                        current == null ? task.getStage() : current.getStage(), latency);
                settle(task, true);
                taskCounter(taskType, "timed_out").increment();
                log.error("[AI_GENERATION] task marked timed out taskId={} errorCode=TASK_TIMEOUT",
                        taskId);
            }
        } catch (ExecutionException exception) {
            fail(task, exception.getCause() == null ? exception : exception.getCause(), elapsedMillis(started));
        } catch (Throwable throwable) {
            fail(task, throwable, elapsedMillis(started));
        } finally {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
            executionRegistry.unregister(taskId);
        }
    }

    private void fail(AiProblemGenerationTask original, Throwable throwable, long latency) {
        AiProblemGenerationTask current = taskMapper.selectById(original.getId());
        if (current == null || current.getStatus() != GenerationStatus.RUNNING.getValue()) {
            log.warn("[AI_GENERATION] failure ignored because task state changed taskId={} errorType={}",
                    original.getId(), throwable.getClass().getSimpleName());
            return;
        }
        if (Integer.valueOf(1).equals(current.getCancelRequested())) {
            taskMapper.markTerminal(current.getId(), GenerationStatus.CANCELLED.getValue(),
                    null, null, null, latency);
            settle(current, false);
            taskCounter(AuthoringTaskType.parse(current.getMode()), "cancelled").increment();
            log.info("[AI_GENERATION] failed worker marked cancelled taskId={} latencyMs={}",
                    current.getId(), latency);
            return;
        }
        String errorCode = classify(throwable);
        String message = safeMessage(throwable);
        int attempts = current.getAttemptCount() == null ? 0 : current.getAttemptCount();
        long retryDelayMs = retryDelayMs(throwable, attempts);
        if (throwable instanceof RejectedExecutionException
                && taskMapper.markCapacityDeferred(current.getId(), current.getStage(),
                new Date(System.currentTimeMillis() + retryDelayMs)) > 0) {
            admissionControl.revertStart(current);
            meterRegistry.counter("ai_generation_capacity_deferrals_total",
                    "type", current.getMode()).increment();
            log.info("[AI_GENERATION] task deferred without consuming retry taskId={} stage={}",
                    current.getId(), current.getStage());
            return;
        }
        if (isRetryable(throwable) && attempts < maxAttempts
                && taskMapper.markRetryDelayed(current.getId(), errorCode, message,
                current.getStage(),
                new Date(System.currentTimeMillis() + retryDelayMs)) > 0) {
            admissionControl.revertStart(current);
            meterRegistry.counter("ai_generation_retries_total",
                    "type", current.getMode(), "reason", errorCode).increment();
            log.warn("[AI_GENERATION] task scheduled for retry taskId={} attempt={} maxAttempts={} errorCode={} errorType={} latencyMs={}",
                    current.getId(), attempts, maxAttempts, errorCode,
                    throwable.getClass().getSimpleName(), latency, throwable);
            return;
        }
        taskMapper.markTerminal(current.getId(), GenerationStatus.FAILED.getValue(),
                errorCode, message, current.getStage(), latency);
        settle(current, isSystemFailure(errorCode));
        taskCounter(AuthoringTaskType.parse(current.getMode()), "failed").increment();
        log.error("[AI_GENERATION] task marked failed taskId={} attempt={} errorCode={} errorType={} latencyMs={}",
                current.getId(), attempts, errorCode, throwable.getClass().getSimpleName(), latency, throwable);
    }

    private void validateCreate(AuthoringTaskType type, AuthoringRequest request,
                                Long userId, String idempotencyKey) {
        requirePositive(userId);
        if (type == null || request == null || !workflowRegistry.requestType(type).isInstance(request)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        try {
            UUID.fromString(idempotencyKey);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "X-Idempotency-Key 必须是 UUID");
        }
        if (type == AuthoringTaskType.PROBLEM_DRAFT) {
            ProblemDraftTaskRequest draft = (ProblemDraftTaskRequest) request;
            if (draft.getRequirements() == null || draft.getRequirements().getTopic() == null
                    || draft.getRequirements().getTopic().isBlank()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "自动出题必须填写主题");
            }
        } else if (type == AuthoringTaskType.TEST_CASES) {
            TestCaseTaskRequest testCases = (TestCaseTaskRequest) request;
            if (testCases.getSourceDraft() == null || testCases.getSourceTaskId() == null
                    || testCases.getSourceTaskId() <= 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成测试用例必须关联已完成的 AI 题目草稿");
            }
            AiProblemGenerationTask source = owned(taskMapper.selectById(testCases.getSourceTaskId()), userId);
            if (!AuthoringTaskType.PROBLEM_DRAFT.name().equals(source.getMode())
                    || source.getStatus() != GenerationStatus.REVIEW_REQUIRED.getValue()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "关联的题目草稿尚未完成");
            }
        } else if (type == AuthoringTaskType.QUALITY_REVIEW) {
            QualityReviewTaskRequest quality = (QualityReviewTaskRequest) request;
            if (quality.getSubmissionId() == null || quality.getSubmissionId() <= 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "AI 质检必须关联待审核提交");
            }
        }
    }

    private void prepareAuthoritativeRequest(AuthoringTaskType type, AuthoringRequest request, Long userId) {
        if (type != AuthoringTaskType.QUALITY_REVIEW || !(request instanceof QualityReviewTaskRequest quality)) {
            return;
        }
        if (quality.getSubmissionId() == null || quality.getSubmissionId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "submissionId 不合法");
        }
        try {
            var response = questionServiceClient.getReviewSubmissionSnapshot(quality.getSubmissionId(), internalToken);
            if (response == null || response.getData() == null) {
                throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "无法读取审核快照");
            }
            quality.setSourceDraft(response.getData());
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AI_UPSTREAM_UNAVAILABLE, "题目审核服务暂时不可用");
        }
    }

    private GenerationTaskVO toOwnedVO(AiProblemGenerationTask task, Long userId) {
        return toVO(owned(task, userId));
    }

    private AiProblemGenerationTask owned(AiProblemGenerationTask task, Long userId) {
        requireTask(task);
        if (!userId.equals(task.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        return task;
    }

    private AiProblemGenerationTask requireTask(AiProblemGenerationTask task) {
        if (task == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "生成任务不存在");
        return task;
    }

    private GenerationTaskVO toVO(AiProblemGenerationTask task) {
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "生成任务不存在");
        }
        GenerationTaskVO result = new GenerationTaskVO();
        result.setTaskId(task.getId());
        result.setTaskType(task.getMode());
        GenerationStatus status = GenerationStatus.fromValue(task.getStatus());
        result.setStatus(status == null ? "UNKNOWN" : status.name());
        result.setStage(task.getStage());
        result.setProgress(task.getProgress());
        result.setErrorCode(task.getErrorCode());
        result.setLastError(task.getLastError());
        result.setLane(task.getLane());
        result.setSourceTaskId(task.getSourceTaskId());
        result.setSubmissionId(task.getSubmissionId());
        result.setTraceId(task.getTraceId());
        result.setModelName(task.getModelName());
        result.setInputTokens(task.getInputTokens());
        result.setOutputTokens(task.getOutputTokens());
        result.setModelCallCount(task.getModelCallCount());
        result.setEstimatedCostMicros(task.getEstimatedCostMicros());
        result.setQuotaCost(task.getQuotaCost());
        result.setQuotaDate(task.getQuotaDate());
        result.setQuotaStatus(task.getQuotaStatus());
        result.setLatencyMs(task.getLatencyMs());
        result.setFailureStage(task.getFailureStage());
        result.setNextAttemptTime(task.getNextAttemptTime());
        result.setDegraded(Integer.valueOf(1).equals(task.getDegraded()));
        result.setCreateTime(task.getCreateTime());
        result.setUpdateTime(task.getUpdateTime());
        if (task.getResultJson() != null) {
            result.setResult(readTree(task.getResultJson()));
        }
        return result;
    }

    private void enqueueSafely(AiProblemGenerationTask task) {
        try {
            streamManager.enqueue(task.getId(), task.getLane() == null
                    ? GenerationLane.forType(AuthoringTaskType.parse(task.getMode()))
                    : GenerationLane.valueOf(task.getLane()));
            // A successful XADD is durable. Move the DB recovery cursor forward so a stopped
            // consumer does not receive another copy on every recovery scan.
            taskMapper.deferPending(task.getId(), new Date(System.currentTimeMillis() + 30_000L));
        } catch (RuntimeException exception) {
            // 数据库 PENDING 记录由 GenerationRecoveryJob 在 Redis 恢复后重新入队。
            log.warn("[AI_GENERATION] task enqueue deferred taskId={} errorType={}",
                    task.getId(), exception.getClass().getSimpleName(), exception);
        }
    }

    private void enqueueSafely(Long taskId) {
        AiProblemGenerationTask task = taskMapper.selectById(taskId);
        if (task != null) enqueueSafely(task);
    }

    @Override
    public GenerationQuotaVO quota(Long userId, String role) {
        return admissionControl.quota(requirePositive(userId), role);
    }

    @Override
    public ArtifactValidationResult validateArtifact(ArtifactValidationRequest request) {
        if (request == null || request.getUserId() == null || request.getTestCasesTaskId() == null
                || request.getSnapshot() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        AiProblemGenerationTask task = owned(taskMapper.selectById(request.getTestCasesTaskId()), request.getUserId());
        if (!AuthoringTaskType.TEST_CASES.name().equals(task.getMode())
                || task.getStatus() != GenerationStatus.REVIEW_REQUIRED.getValue()
                || task.getSourceTaskId() == null || task.getResultJson() == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "测试用例任务尚未形成可提交产物");
        }
        try {
            TestCaseTaskRequest sourceRequest = objectMapper.readValue(task.getRequestJson(), TestCaseTaskRequest.class);
            JsonNode result = objectMapper.readTree(task.getResultJson()).path("data");
            TestCaseArtifact artifact = objectMapper.treeToValue(result, TestCaseArtifact.class);
            ProblemSourceDraft expected = objectMapper.convertValue(sourceRequest.getSourceDraft(), ProblemSourceDraft.class);
            expected.setJudgeCase(artifact.getJudgeCases() == null
                    ? new ArrayList<>() : new ArrayList<>(artifact.getJudgeCases()));
            String expectedHash = ExecutionFingerprint.of(expected, objectMapper);
            String submittedHash = ExecutionFingerprint.of(request.getSnapshot(), objectMapper);
            if (!expectedHash.equals(submittedHash)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        "题意、答案、评测限制或测试用例已变化，请重新生成测试用例");
            }
            return new ArtifactValidationResult(true, task.getSourceTaskId(), expectedHash);
        } catch (BusinessException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 产物无法验证");
        }
    }

    private void settle(AiProblemGenerationTask task, boolean refund) {
        AiProblemGenerationTask durable = taskMapper.selectById(task.getId());
        AiProblemGenerationTask settlementTask = durable == null ? task : durable;
        if (admissionControl.settle(settlementTask, refund)) {
            taskMapper.updateQuotaStatus(task.getId(), "RESERVED", refund ? "REFUNDED" : "SETTLED");
        }
    }

    private boolean isSystemFailure(String errorCode) {
        return List.of("TASK_TIMEOUT", "MODEL_UNAVAILABLE", "MODEL_OUTPUT_INVALID",
                "DEPENDENCY_RATE_LIMITED", "DEPENDENCY_UNAVAILABLE", "DEPENDENCY_TIMEOUT",
                "WORKER_BUSY", "GENERATION_FAILED").contains(errorCode);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("生成任务数据无法序列化", exception);
        }
    }

    private AuthoringRequest readRequest(AuthoringTaskType type, String value) {
        try {
            return objectMapper.readValue(value, workflowRegistry.requestType(type));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI 题目创作请求无法解析", exception);
        }
    }

    private JsonNode readTree(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI 题目创作结果无法解析", exception);
        }
    }

    private boolean cancellationRequested(Long taskId) {
        AiProblemGenerationTask current = taskMapper.selectById(taskId);
        return current == null || Integer.valueOf(1).equals(current.getCancelRequested());
    }

    private long timeoutMs(AuthoringTaskType type) {
        return switch (type) {
            case PROBLEM_DRAFT -> problemDraftTimeoutMs;
            case TEST_CASES -> testCasesTimeoutMs;
            case QUALITY_REVIEW -> qualityReviewTimeoutMs;
        };
    }

    private Long requirePositive(Long value) {
        if (value == null || value <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        return value;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成幂等键", exception);
        }
    }

    private boolean isRetryable(Throwable throwable) {
        return isTruncatedToolArguments(throwable)
                || throwable instanceof BusinessException businessException
                && businessException.getCode() == ErrorCode.AI_UPSTREAM_UNAVAILABLE.getCode()
                || throwable instanceof ResourceAccessException
                || throwable instanceof TransientAiException
                || throwable instanceof HttpServerErrorException
                || throwable instanceof HttpStatusCodeException httpException
                && httpException.getStatusCode().value() == 429
                || throwable instanceof TimeoutException
                || throwable instanceof RejectedExecutionException;
    }

    private String classify(Throwable throwable) {
        if (throwable instanceof BusinessException businessException
                && businessException.getCode() == ErrorCode.AI_UPSTREAM_UNAVAILABLE.getCode()) {
            return "DEPENDENCY_UNAVAILABLE";
        }
        if (isTruncatedToolArguments(throwable)) return "MODEL_OUTPUT_INVALID";
        if (throwable instanceof SandboxConfigurationException) return "SANDBOX_MISCONFIGURED";
        if (throwable instanceof GenerationValidationException) return "QUALITY_GATE_FAILED";
        if (throwable instanceof TransientAiException) return "MODEL_UNAVAILABLE";
        if (throwable instanceof HttpStatusCodeException httpException
                && httpException.getStatusCode().value() == 429) return "DEPENDENCY_RATE_LIMITED";
        if (throwable instanceof HttpServerErrorException) return "DEPENDENCY_UNAVAILABLE";
        if (throwable instanceof ResourceAccessException) return "DEPENDENCY_UNAVAILABLE";
        if (throwable instanceof TimeoutException) return "DEPENDENCY_TIMEOUT";
        if (throwable instanceof RejectedExecutionException) return "WORKER_BUSY";
        return "GENERATION_FAILED";
    }

    private String safeMessage(Throwable throwable) {
        if (throwable instanceof GenerationValidationException && throwable.getMessage() != null) {
            return truncate(throwable.getMessage());
        }
        String code = classify(throwable);
        return switch (code) {
            case "SANDBOX_MISCONFIGURED" -> "代码沙箱运行环境配置错误";
            case "DEPENDENCY_UNAVAILABLE" -> "模型或代码沙箱暂时不可用";
            case "MODEL_UNAVAILABLE" -> "模型服务暂时不可用";
            case "MODEL_OUTPUT_INVALID" -> "模型工具参数不完整，已安排重试";
            case "DEPENDENCY_RATE_LIMITED" -> "模型或代码沙箱触发限流";
            case "DEPENDENCY_TIMEOUT" -> "模型或代码沙箱调用超时";
            case "WORKER_BUSY" -> "AI 出题执行资源繁忙";
            default -> "AI 出题执行失败";
        };
    }

    private String truncate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private boolean isTruncatedToolArguments(Throwable throwable) {
        if (!(throwable instanceof ToolExecutionException)) return false;
        Throwable cause = throwable.getCause();
        while (cause != null && cause != cause.getCause()) {
            if (cause instanceof JsonEOFException) return true;
            cause = cause.getCause();
        }
        return false;
    }

    private long retryDelayMs(Throwable throwable, int attempts) {
        long configured = attempts <= 1 ? 1_000L : 3_000L;
        if (throwable instanceof HttpStatusCodeException httpException
                && httpException.getStatusCode().value() == 429) {
            String retryAfter = httpException.getResponseHeaders() == null
                    ? null : httpException.getResponseHeaders().getFirst("Retry-After");
            if (retryAfter != null) {
                try {
                    configured = Math.max(configured, Math.min(60_000L,
                            TimeUnit.SECONDS.toMillis(Long.parseLong(retryAfter.trim()))));
                } catch (NumberFormatException ignored) {
                    try {
                        long wait = java.time.ZonedDateTime.parse(retryAfter.trim(),
                                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toInstant().toEpochMilli() - System.currentTimeMillis();
                        configured = Math.max(configured, Math.min(60_000L, Math.max(0L, wait)));
                    } catch (java.time.format.DateTimeParseException invalidDate) {
                        // Ignore malformed provider hints and retain bounded backoff.
                    }
                }
            }
        }
        return configured + java.util.concurrent.ThreadLocalRandom.current().nextLong(250L, 1_000L);
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private io.micrometer.core.instrument.Counter taskCounter(AuthoringTaskType type, String outcome) {
        return meterRegistry.counter("ai_generation_tasks_total",
                "type", type.name(), "outcome", outcome);
    }
}

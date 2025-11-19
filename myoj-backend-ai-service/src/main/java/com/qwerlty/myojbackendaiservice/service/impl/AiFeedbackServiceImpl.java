package com.qwerlty.myojbackendaiservice.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.client.QuestionServiceClient;
import com.qwerlty.myojbackendaiservice.common.BaseResponse;
import com.qwerlty.myojbackendaiservice.common.ErrorCode;
import com.qwerlty.myojbackendaiservice.exception.BusinessException;
import com.qwerlty.myojbackendaiservice.manager.AiChatManager;
import com.qwerlty.myojbackendaiservice.manager.RedisLimiterManager;
import com.qwerlty.myojbackendaiservice.mapper.AiFeedbackTaskMapper;
import com.qwerlty.myojbackendaiservice.model.dto.AiAnalysisResult;
import com.qwerlty.myojbackendaiservice.model.dto.AiSubmissionContextDTO;
import com.qwerlty.myojbackendaiservice.model.entity.AiFeedbackTask;
import com.qwerlty.myojbackendaiservice.model.enums.AiFeedbackStatusEnum;
import com.qwerlty.myojbackendaiservice.model.vo.AiFeedbackResultVO;
import com.qwerlty.myojbackendaiservice.model.vo.AiFeedbackTaskVO;
import feign.FeignException;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class AiFeedbackServiceImpl implements com.qwerlty.myojbackendaiservice.service.AiFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(AiFeedbackServiceImpl.class);
    private static final long[] EXECUTE_BACKOFF_MS = {5_000L, 30_000L, 120_000L};

    private final AiFeedbackTaskMapper taskMapper;
    private final QuestionServiceClient questionServiceClient;
    private final RedisLimiterManager redisLimiterManager;
    private final AiChatManager aiChatManager;
    private final ObjectMapper objectMapper;
    private final ExecutorService aiAnalysisExecutor;
    private final MeterRegistry meterRegistry;
    private final String internalToken;
    private final String modelName;
    private final String promptVersion;
    private final String knowledgeVersion;
    private final int maxExecuteRetry;
    private final long analysisTimeoutMs;

    public AiFeedbackServiceImpl(
            AiFeedbackTaskMapper taskMapper,
            QuestionServiceClient questionServiceClient,
            RedisLimiterManager redisLimiterManager,
            AiChatManager aiChatManager,
            ObjectMapper objectMapper,
            @Qualifier("aiAnalysisExecutor") ExecutorService aiAnalysisExecutor,
            MeterRegistry meterRegistry,
            @Value("${myoj.ai.internal-token}") String internalToken,
            @Value("${myoj.ai.model-name}") String modelName,
            @Value("${myoj.ai.prompt-version}") String promptVersion,
            @Value("${myoj.ai.knowledge-version}") String knowledgeVersion,
            @Value("${myoj.ai.task.max-execute-retry:3}") int maxExecuteRetry,
            @Value("${myoj.ai.task.analysis-timeout-ms:90000}") long analysisTimeoutMs) {
        this.taskMapper = taskMapper;
        this.questionServiceClient = questionServiceClient;
        this.redisLimiterManager = redisLimiterManager;
        this.aiChatManager = aiChatManager;
        this.objectMapper = objectMapper;
        this.aiAnalysisExecutor = aiAnalysisExecutor;
        this.meterRegistry = meterRegistry;
        this.internalToken = internalToken;
        this.modelName = modelName;
        this.promptVersion = promptVersion;
        this.knowledgeVersion = knowledgeVersion;
        this.maxExecuteRetry = maxExecuteRetry;
        this.analysisTimeoutMs = analysisTimeoutMs;
    }

    @Override
    @Transactional
    public AiFeedbackTaskVO createTask(Long submissionId, Long userId) {
        requirePositive(submissionId, "submissionId");
        requirePositive(userId, "userId");
        String requestKey = requestKey(userId, submissionId, promptVersion, knowledgeVersion);

        AiFeedbackTask existing = taskMapper.selectByRequestKey(requestKey);
        if (existing != null && !isRetryableTerminal(existing.getStatus())) {
            return toVO(existing);
        }

        AiSubmissionContextDTO context = loadSubmissionContext(submissionId, userId);
        if (existing != null) {
            checkRateLimit(userId);
            if (taskMapper.resetFailedTask(existing.getId(), modelName) <= 0) {
                redisLimiterManager.refund(userId);
            } else {
                meterRegistry.counter("ai_feedback_task_created_total", "type", "manual_retry").increment();
            }
            return toVO(taskMapper.selectById(existing.getId()));
        }

        checkRateLimit(userId);
        Date now = new Date();
        AiFeedbackTask task = new AiFeedbackTask();
        task.setRequestKey(requestKey);
        task.setUserId(userId);
        task.setSubmissionId(submissionId);
        task.setQuestionId(context.getQuestionId());
        task.setStatus(AiFeedbackStatusEnum.PENDING.getValue());
        task.setModelName(modelName);
        task.setPromptVersion(promptVersion);
        task.setKnowledgeVersion(knowledgeVersion);
        task.setInputTokens(0);
        task.setOutputTokens(0);
        task.setLatencyMs(0L);
        task.setDispatchRetryCount(0);
        task.setExecuteRetryCount(0);
        task.setNextRetryTime(now);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException duplicateKeyException) {
            redisLimiterManager.refund(userId);
            return toVO(taskMapper.selectByRequestKey(requestKey));
        } catch (RuntimeException exception) {
            redisLimiterManager.refund(userId);
            throw exception;
        }
        meterRegistry.counter("ai_feedback_task_created_total", "type", "new").increment();
        return toVO(task);
    }

    @Override
    public AiFeedbackTaskVO getTask(Long taskId, Long userId) {
        requirePositive(taskId, "taskId");
        requirePositive(userId, "userId");
        AiFeedbackTask task = taskMapper.selectById(taskId);
        return toOwnedVO(task, userId);
    }

    @Override
    public AiFeedbackTaskVO getLatestBySubmission(Long submissionId, Long userId) {
        requirePositive(submissionId, "submissionId");
        requirePositive(userId, "userId");
        AiFeedbackTask task = taskMapper.selectLatest(userId, submissionId);
        return toOwnedVO(task, userId);
    }

    @Override
    public void executeTask(Long taskId) {
        AiFeedbackTask task = taskMapper.selectById(taskId);
        if (task == null || !Integer.valueOf(AiFeedbackStatusEnum.RUNNING.getValue()).equals(task.getStatus())) {
            return;
        }

        long startNanos = System.nanoTime();
        if (task.getCreateTime() != null) {
            DistributionSummary.builder("ai_feedback_queue_duration_ms")
                    .register(meterRegistry)
                    .record(Math.max(0, System.currentTimeMillis() - task.getCreateTime().getTime()));
        }

        Future<AiAnalysisResult> future = null;
        try {
            AiSubmissionContextDTO context = loadSubmissionContext(task.getSubmissionId(), task.getUserId());
            future = aiAnalysisExecutor.submit(() -> aiChatManager.analyze(context, task.getUserId()));
            AiAnalysisResult analysis = future.get(analysisTimeoutMs, TimeUnit.MILLISECONDS);
            long latencyMs = elapsedMillis(startNanos);
            String resultJson = objectMapper.writeValueAsString(analysis.getResult());
            String citationsJson = objectMapper.writeValueAsString(analysis.getCitations());
            int updated = taskMapper.markSuccess(
                    taskId,
                    resultJson,
                    citationsJson,
                    analysis.getInputTokens(),
                    analysis.getOutputTokens(),
                    latencyMs);
            if (updated > 0) {
                recordSuccessMetrics(analysis, latencyMs);
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            handleExecutionFailure(task, interruptedException, elapsedMillis(startNanos));
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause() == null ? executionException : executionException.getCause();
            handleExecutionFailure(task, cause, elapsedMillis(startNanos));
        } catch (Throwable throwable) {
            handleExecutionFailure(task, throwable, elapsedMillis(startNanos));
        } finally {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private AiSubmissionContextDTO loadSubmissionContext(Long submissionId, Long userId) {
        BaseResponse<AiSubmissionContextDTO> response = questionServiceClient.getSubmissionContext(
                submissionId, userId, internalToken);
        if (response == null) {
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "Question Service 未返回数据");
        }
        if (response.getCode() != ErrorCode.SUCCESS.getCode()) {
            throw mapRemoteError(response.getCode());
        }
        AiSubmissionContextDTO context = response.getData();
        if (context == null
                || !submissionId.equals(context.getSubmissionId())
                || context.getQuestionId() == null) {
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "提交上下文不完整");
        }
        return context;
    }

    private BusinessException mapRemoteError(int code) {
        if (code == ErrorCode.NO_AUTH_ERROR.getCode() || code == ErrorCode.FORBIDDEN_ERROR.getCode()) {
            return new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权分析该提交");
        }
        if (code == ErrorCode.NOT_FOUND_ERROR.getCode()) {
            return new BusinessException(ErrorCode.NOT_FOUND_ERROR, "提交或题目不存在");
        }
        if (code == ErrorCode.PARAMS_ERROR.getCode()) {
            return new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        return new BusinessException(ErrorCode.OPERATION_ERROR, "提交尚未完成或暂不可分析");
    }

    private void handleExecutionFailure(AiFeedbackTask originalTask, Throwable throwable, long latencyMs) {
        AiFeedbackTask current = taskMapper.selectById(originalTask.getId());
        if (current == null || !Integer.valueOf(AiFeedbackStatusEnum.RUNNING.getValue()).equals(current.getStatus())) {
            return;
        }
        int retryCount = current.getExecuteRetryCount() == null ? 0 : current.getExecuteRetryCount();
        String errorCode = classifyErrorCode(throwable);
        String safeMessage = safeErrorMessage(errorCode);
        boolean retryable = isRetryable(throwable);

        log.warn("AI task execution failed: taskId={}, errorType={}, retry={}",
                originalTask.getId(), throwable.getClass().getSimpleName(), retryCount);
        if (retryable && retryCount < maxExecuteRetry) {
            taskMapper.markExecutionRetry(
                    originalTask.getId(),
                    new Date(System.currentTimeMillis() + executeBackoffMs(retryCount)),
                    errorCode,
                    safeMessage);
            meterRegistry.counter("ai_feedback_task_retry_total", "reason", errorCode).increment();
            return;
        }

        int terminalStatus = "AI_TIMEOUT".equals(errorCode)
                ? AiFeedbackStatusEnum.TIMEOUT.getValue()
                : AiFeedbackStatusEnum.FAILED.getValue();
        taskMapper.markExecutionTerminal(
                originalTask.getId(), terminalStatus, errorCode, safeMessage, latencyMs);
        meterRegistry.counter("ai_feedback_task_completed_total", "outcome",
                terminalStatus == AiFeedbackStatusEnum.TIMEOUT.getValue() ? "timeout" : "failed").increment();
    }

    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof BusinessException) {
            return false;
        }
        if (throwable instanceof FeignException feignException) {
            return feignException.status() == 429 || feignException.status() < 0 || feignException.status() >= 500;
        }
        if (throwable instanceof JsonProcessingException) {
            return true;
        }
        String className = throwable.getClass().getName();
        return throwable instanceof TimeoutException
                || throwable instanceof InterruptedException
                || throwable instanceof IllegalStateException
                || className.contains("TransientAiException")
                || className.contains("RetryableException")
                || className.contains("ResourceAccessException")
                || className.contains("ConnectException")
                || className.contains("TimeoutException")
                || className.contains("RejectedExecutionException");
    }

    private String classifyErrorCode(Throwable throwable) {
        if (throwable instanceof TimeoutException
                || throwable.getClass().getSimpleName().contains("Timeout")) {
            return "AI_TIMEOUT";
        }
        if (throwable instanceof BusinessException) {
            return "SUBMISSION_CONTEXT_INVALID";
        }
        if (throwable instanceof IllegalStateException || throwable instanceof JsonProcessingException) {
            return "MODEL_OUTPUT_INVALID";
        }
        if (throwable instanceof FeignException) {
            return "DEPENDENCY_UNAVAILABLE";
        }
        return "AI_EXECUTION_FAILED";
    }

    private String safeErrorMessage(String errorCode) {
        return switch (errorCode) {
            case "AI_TIMEOUT" -> "AI 分析超时，请稍后重试";
            case "SUBMISSION_CONTEXT_INVALID" -> "提交上下文无效或已不可访问";
            case "MODEL_OUTPUT_INVALID" -> "模型未返回有效的结构化分析结果";
            case "DEPENDENCY_UNAVAILABLE" -> "依赖服务暂时不可用";
            default -> "AI 分析执行失败";
        };
    }

    private void recordSuccessMetrics(AiAnalysisResult analysis, long latencyMs) {
        meterRegistry.counter("ai_feedback_task_completed_total", "outcome", "success").increment();
        Timer.builder("ai_feedback_model_latency").register(meterRegistry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
        DistributionSummary.builder("ai_feedback_tokens")
                .tag("type", "input")
                .register(meterRegistry)
                .record(analysis.getInputTokens());
        DistributionSummary.builder("ai_feedback_tokens")
                .tag("type", "output")
                .register(meterRegistry)
                .record(analysis.getOutputTokens());
    }

    private AiFeedbackTaskVO toOwnedVO(AiFeedbackTask task, Long userId) {
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "AI 分析任务不存在");
        }
        if (!userId.equals(task.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        return toVO(task);
    }

    private AiFeedbackTaskVO toVO(AiFeedbackTask task) {
        if (task == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 分析任务读取失败");
        }
        AiFeedbackTaskVO vo = new AiFeedbackTaskVO();
        vo.setTaskId(task.getId());
        vo.setSubmissionId(task.getSubmissionId());
        vo.setQuestionId(task.getQuestionId());
        vo.setStatus(AiFeedbackStatusEnum.fromValue(task.getStatus()).name());
        vo.setErrorCode(task.getErrorCode());
        vo.setLastError(task.getLastError());
        vo.setCreateTime(task.getCreateTime());
        vo.setUpdateTime(task.getUpdateTime());
        if (task.getResultJson() != null) {
            try {
                vo.setResult(objectMapper.readValue(task.getResultJson(), AiFeedbackResultVO.class));
            } catch (JsonProcessingException exception) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 分析结果读取失败");
            }
        }
        return vo;
    }

    private boolean isRetryableTerminal(Integer status) {
        return Integer.valueOf(AiFeedbackStatusEnum.FAILED.getValue()).equals(status)
                || Integer.valueOf(AiFeedbackStatusEnum.TIMEOUT.getValue()).equals(status);
    }

    private void checkRateLimit(Long userId) {
        if (!redisLimiterManager.tryAcquire(userId)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST);
        }
    }

    private void requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, name + " 必须为正整数");
        }
    }

    private String requestKey(Long userId, Long submissionId, String prompt, String knowledge) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String source = userId + ":" + submissionId + ":" + prompt + ":" + knowledge;
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成幂等键", exception);
        }
    }

    private long executeBackoffMs(int retryCount) {
        int index = Math.max(0, Math.min(retryCount - 1, EXECUTE_BACKOFF_MS.length - 1));
        return EXECUTE_BACKOFF_MS[index];
    }

    private long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}

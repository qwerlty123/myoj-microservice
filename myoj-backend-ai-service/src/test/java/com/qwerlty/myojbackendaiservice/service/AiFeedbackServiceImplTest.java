package com.qwerlty.myojbackendaiservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.client.QuestionServiceClient;
import com.qwerlty.myojbackendaiservice.common.BaseResponse;
import com.qwerlty.myojbackendaiservice.exception.BusinessException;
import com.qwerlty.myojbackendaiservice.manager.AiChatManager;
import com.qwerlty.myojbackendaiservice.manager.RedisLimiterManager;
import com.qwerlty.myojbackendaiservice.mapper.AiFeedbackTaskMapper;
import com.qwerlty.myojbackendaiservice.model.dto.AiAnalysisResult;
import com.qwerlty.myojbackendaiservice.model.dto.AiSubmissionContextDTO;
import com.qwerlty.myojbackendaiservice.model.entity.AiFeedbackTask;
import com.qwerlty.myojbackendaiservice.model.enums.AiFeedbackStatusEnum;
import com.qwerlty.myojbackendaiservice.queue.AiFeedbackStreamManager;
import com.qwerlty.myojbackendaiservice.service.impl.AiFeedbackServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiFeedbackServiceImplTest {

    private AiFeedbackTaskMapper taskMapper;
    private QuestionServiceClient questionServiceClient;
    private RedisLimiterManager limiterManager;
    private AiFeedbackStreamManager streamManager;
    private ExecutorService executorService;
    private AiFeedbackServiceImpl service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(AiFeedbackTaskMapper.class);
        questionServiceClient = mock(QuestionServiceClient.class);
        limiterManager = mock(RedisLimiterManager.class);
        streamManager = mock(AiFeedbackStreamManager.class);
        executorService = mock(ExecutorService.class);
        service = new AiFeedbackServiceImpl(
                taskMapper,
                questionServiceClient,
                limiterManager,
                mock(AiChatManager.class),
                new ObjectMapper(),
                executorService,
                new SimpleMeterRegistry(),
                streamManager,
                "internal-token",
                "test-model",
                "prompt-v1",
                "knowledge-v1",
                3,
                90_000L);
    }

    @Test
    void idempotentHitDoesNotConsumeRateLimitOrCallQuestionService() {
        AiFeedbackTask existing = task(101L, 7L, 9L, AiFeedbackStatusEnum.RUNNING);
        when(taskMapper.selectByRequestKey(anyString())).thenReturn(existing);

        assertThat(service.createTask(9L, 7L).getTaskId()).isEqualTo(101L);

        verify(limiterManager, never()).tryAcquire(anyLong());
        verify(questionServiceClient, never()).getSubmissionContext(anyLong(), anyLong(), anyString());
        verify(taskMapper, never()).insert(any(AiFeedbackTask.class));
    }

    @Test
    void pendingIdempotentHitCanRepairDirectEnqueueGapWithoutAnotherModelTask() {
        AiFeedbackTask existing = task(107L, 7L, 9L, AiFeedbackStatusEnum.PENDING);
        when(taskMapper.selectByRequestKey(anyString())).thenReturn(existing);

        assertThat(service.createTask(9L, 7L).getTaskId()).isEqualTo(107L);

        verify(streamManager).enqueue(107L);
        verify(limiterManager, never()).tryAcquire(anyLong());
        verify(taskMapper, never()).insert(any(AiFeedbackTask.class));
    }

    @Test
    void newTaskValidatesOwnershipThenPersistsPendingRecord() {
        AiSubmissionContextDTO context = new AiSubmissionContextDTO();
        context.setSubmissionId(9L);
        context.setQuestionId(88L);
        when(questionServiceClient.getSubmissionContext(9L, 7L, "internal-token"))
                .thenReturn(new BaseResponse<>(0, context, "ok"));
        when(limiterManager.tryAcquire(7L)).thenReturn(true);
        when(taskMapper.resetFailedTask(103L, "test-model")).thenReturn(1);
        when(taskMapper.insert(any(AiFeedbackTask.class))).thenAnswer(invocation -> {
            AiFeedbackTask inserted = invocation.getArgument(0);
            inserted.setId(102L);
            return 1;
        });

        var result = service.createTask(9L, 7L);

        assertThat(result.getTaskId()).isEqualTo(102L);
        assertThat(result.getQuestionId()).isEqualTo(88L);
        assertThat(result.getStatus()).isEqualTo("PENDING");
        verify(limiterManager).tryAcquire(7L);
        verify(taskMapper).insert(any(AiFeedbackTask.class));
        verify(streamManager).enqueue(102L);
    }

    @Test
    void failedTaskCanBeResetButConsumesRateLimit() {
        AiFeedbackTask failed = task(103L, 7L, 9L, AiFeedbackStatusEnum.FAILED);
        AiFeedbackTask pending = task(103L, 7L, 9L, AiFeedbackStatusEnum.PENDING);
        when(taskMapper.selectByRequestKey(anyString())).thenReturn(failed);
        when(taskMapper.selectById(103L)).thenReturn(pending);
        AiSubmissionContextDTO context = new AiSubmissionContextDTO();
        context.setSubmissionId(9L);
        context.setQuestionId(88L);
        when(questionServiceClient.getSubmissionContext(9L, 7L, "internal-token"))
                .thenReturn(new BaseResponse<>(0, context, "ok"));
        when(limiterManager.tryAcquire(7L)).thenReturn(true);
        when(taskMapper.resetFailedTask(103L, "test-model")).thenReturn(1);

        assertThat(service.createTask(9L, 7L).getStatus()).isEqualTo("PENDING");

        verify(taskMapper).resetFailedTask(103L, "test-model");
        verify(limiterManager).tryAcquire(7L);
        verify(streamManager).enqueue(103L);
    }

    @Test
    void rateLimitRejectsNewTask() {
        AiSubmissionContextDTO context = new AiSubmissionContextDTO();
        context.setSubmissionId(9L);
        context.setQuestionId(88L);
        when(questionServiceClient.getSubmissionContext(9L, 7L, "internal-token"))
                .thenReturn(new BaseResponse<>(0, context, "ok"));
        when(limiterManager.tryAcquire(7L)).thenReturn(false);

        assertThatThrownBy(() -> service.createTask(9L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(42900);
        verify(taskMapper, never()).insert(any(AiFeedbackTask.class));
    }

    @Test
    void directStreamFailureMarksBusinessTaskFailedWithoutOutboxScanning() {
        AiSubmissionContextDTO context = new AiSubmissionContextDTO();
        context.setSubmissionId(9L);
        context.setQuestionId(88L);
        when(questionServiceClient.getSubmissionContext(9L, 7L, "internal-token"))
                .thenReturn(new BaseResponse<>(0, context, "ok"));
        when(limiterManager.tryAcquire(7L)).thenReturn(true);
        when(taskMapper.insert(any(AiFeedbackTask.class))).thenAnswer(invocation -> {
            AiFeedbackTask inserted = invocation.getArgument(0);
            inserted.setId(105L);
            return 1;
        });
        when(streamManager.enqueue(105L)).thenThrow(new IllegalStateException("redis unavailable"));
        when(taskMapper.selectById(105L)).thenReturn(task(105L, 7L, 9L, AiFeedbackStatusEnum.FAILED));

        var result = service.createTask(9L, 7L);

        assertThat(result.getStatus()).isEqualTo("FAILED");
        verify(taskMapper).markPendingTerminal(
                105L,
                AiFeedbackStatusEnum.FAILED.getValue(),
                "QUEUE_UNAVAILABLE",
                "AI 异步队列暂时不可用，请稍后重试");
    }

    @Test
    void transientModelFailureRequeuesDirectlyWithoutDatabaseScheduling() throws Exception {
        AiFeedbackTask running = task(106L, 7L, 9L, AiFeedbackStatusEnum.RUNNING);
        running.setAttemptCount(1);
        when(taskMapper.selectById(106L)).thenReturn(running);
        AiSubmissionContextDTO context = new AiSubmissionContextDTO();
        context.setSubmissionId(9L);
        context.setQuestionId(88L);
        when(questionServiceClient.getSubmissionContext(9L, 7L, "internal-token"))
                .thenReturn(new BaseResponse<>(0, context, "ok"));
        @SuppressWarnings("unchecked")
        Future<AiAnalysisResult> future = mock(Future.class);
        when(executorService.submit(any(Callable.class))).thenReturn(future);
        when(future.get(90_000L, TimeUnit.MILLISECONDS))
                .thenThrow(new ExecutionException(new TimeoutException("temporary timeout")));
        when(taskMapper.markExecutionRetry(106L, "AI_TIMEOUT", "AI 分析超时，请稍后重试"))
                .thenReturn(1);

        service.executeTask(106L);

        verify(taskMapper).markExecutionRetry(106L, "AI_TIMEOUT", "AI 分析超时，请稍后重试");
        verify(streamManager).enqueue(106L);
    }

    @Test
    void historyIsScopedToCurrentUserAndSubmission() {
        AiFeedbackTask finished = task(104L, 7L, 9L, AiFeedbackStatusEnum.SUCCESS);
        when(taskMapper.countHistory(7L, 9L)).thenReturn(1L);
        when(taskMapper.listHistory(7L, 9L, 0L, 10)).thenReturn(java.util.List.of(finished));

        var page = service.getHistory(7L, 9L, 1, 10);

        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getRecords()).singleElement()
                .extracting("taskId")
                .isEqualTo(104L);
    }

    private AiFeedbackTask task(Long taskId,
                                Long userId,
                                Long submissionId,
                                AiFeedbackStatusEnum status) {
        AiFeedbackTask task = new AiFeedbackTask();
        task.setId(taskId);
        task.setUserId(userId);
        task.setSubmissionId(submissionId);
        task.setQuestionId(88L);
        task.setStatus(status.getValue());
        task.setCreateTime(new Date());
        task.setUpdateTime(new Date());
        return task;
    }
}

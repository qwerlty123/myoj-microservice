package com.qwerlty.myojbackendaiservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.client.QuestionServiceClient;
import com.qwerlty.myojbackendaiservice.common.BaseResponse;
import com.qwerlty.myojbackendaiservice.exception.BusinessException;
import com.qwerlty.myojbackendaiservice.manager.AiChatManager;
import com.qwerlty.myojbackendaiservice.manager.RedisLimiterManager;
import com.qwerlty.myojbackendaiservice.mapper.AiFeedbackTaskMapper;
import com.qwerlty.myojbackendaiservice.model.dto.AiSubmissionContextDTO;
import com.qwerlty.myojbackendaiservice.model.entity.AiFeedbackTask;
import com.qwerlty.myojbackendaiservice.model.enums.AiFeedbackStatusEnum;
import com.qwerlty.myojbackendaiservice.service.impl.AiFeedbackServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.concurrent.ExecutorService;

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
    private AiFeedbackServiceImpl service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(AiFeedbackTaskMapper.class);
        questionServiceClient = mock(QuestionServiceClient.class);
        limiterManager = mock(RedisLimiterManager.class);
        service = new AiFeedbackServiceImpl(
                taskMapper,
                questionServiceClient,
                limiterManager,
                mock(AiChatManager.class),
                new ObjectMapper(),
                mock(ExecutorService.class),
                new SimpleMeterRegistry(),
                "internal-token",
                "test-model",
                "prompt-v1",
                "knowledge-v1",
                3,
                90_000L);
    }

    @Test
    void idempotentHitDoesNotConsumeRateLimitOrCallQuestionService() {
        AiFeedbackTask existing = task(101L, 7L, 9L, AiFeedbackStatusEnum.QUEUED);
        when(taskMapper.selectByRequestKey(anyString())).thenReturn(existing);

        assertThat(service.createTask(9L, 7L).getTaskId()).isEqualTo(101L);

        verify(limiterManager, never()).tryAcquire(anyLong());
        verify(questionServiceClient, never()).getSubmissionContext(anyLong(), anyLong(), anyString());
        verify(taskMapper, never()).insert(any(AiFeedbackTask.class));
    }

    @Test
    void newTaskValidatesOwnershipThenPersistsPendingOutboxRecord() {
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

        assertThat(service.createTask(9L, 7L).getStatus()).isEqualTo("PENDING");

        verify(taskMapper).resetFailedTask(103L, "test-model");
        verify(limiterManager).tryAcquire(7L);
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

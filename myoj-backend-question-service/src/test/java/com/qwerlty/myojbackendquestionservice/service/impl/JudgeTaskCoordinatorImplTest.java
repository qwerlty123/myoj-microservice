package com.qwerlty.myojbackendquestionservice.service.impl;

import cn.hutool.json.JSONUtil;
import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskCompleteRequest;
import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskMessage;
import com.qwerlty.myojbackendmodel.model.enums.QuestionSubmitStatusEnum;
import com.qwerlty.myojbackendquestionservice.mapper.JudgeTaskOutboxMapper;
import com.qwerlty.myojbackendquestionservice.mapper.QuestionSubmitMapper;
import com.qwerlty.myojbackendquestionservice.model.entity.JudgeTaskOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JudgeTaskCoordinatorImplTest {

    @Mock
    private QuestionSubmitMapper questionSubmitMapper;

    @Mock
    private JudgeTaskOutboxMapper judgeTaskOutboxMapper;

    @InjectMocks
    private JudgeTaskCoordinatorImpl coordinator;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(coordinator, "maxJudgeRetry", 3);
        ReflectionTestUtils.setField(coordinator, "retryBackoffMs", 5000L);
    }

    @Test
    void retryAdvancesAttemptAndCreatesOneOutbox() {
        when(questionSubmitMapper.retryRunningAsWaiting(
                eq(10L), eq(1), eq(3), any(Date.class), eq("sandbox unavailable"))).thenReturn(1);
        when(judgeTaskOutboxMapper.insert(any())).thenReturn(1);

        assertTrue(coordinator.scheduleRetry(10L, 1, "sandbox unavailable"));

        ArgumentCaptor<JudgeTaskOutbox> captor = ArgumentCaptor.forClass(JudgeTaskOutbox.class);
        verify(judgeTaskOutboxMapper).insert(captor.capture());
        JudgeTaskOutbox outbox = captor.getValue();
        assertEquals(2, outbox.getJudgeAttempt());
        assertEquals(JudgeTaskOutbox.STATUS_PENDING, outbox.getStatus());
        JudgeTaskMessage message = JSONUtil.toBean(outbox.getPayload(), JudgeTaskMessage.class);
        assertEquals(outbox.getEventId(), message.getMessageId());
        assertEquals(2, message.getJudgeAttempt());
    }

    @Test
    void retryExhaustionMarksSameAttemptFailedWithoutNewOutbox() {
        when(questionSubmitMapper.retryRunningAsWaiting(
                eq(10L), eq(4), eq(3), any(Date.class), eq("still failing"))).thenReturn(0);
        when(questionSubmitMapper.markFailedAfterRetryExhausted(
                eq(10L), eq(4), eq(3), any(), eq("still failing"))).thenReturn(1);

        assertTrue(coordinator.scheduleRetry(10L, 4, "still failing"));

        verify(judgeTaskOutboxMapper, never()).insert(any());
    }

    @Test
    void completionCarriesAttemptFence() {
        JudgeTaskCompleteRequest request = new JudgeTaskCompleteRequest();
        request.setSubmissionId(10L);
        request.setJudgeAttempt(2);
        request.setStatus(QuestionSubmitStatusEnum.SUCCEED.getValue());
        request.setJudgeInfo("{}");
        when(questionSubmitMapper.finishFromRunning(10L, 2,
                QuestionSubmitStatusEnum.SUCCEED.getValue(), "{}", null)).thenReturn(1);

        assertTrue(coordinator.complete(request));

        verify(questionSubmitMapper).finishFromRunning(10L, 2,
                QuestionSubmitStatusEnum.SUCCEED.getValue(), "{}", null);
    }
}

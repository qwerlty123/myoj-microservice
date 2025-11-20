package com.qwerlty.myojbackendquestionservice.service;

import com.qwerlty.myojbackendquestionservice.mapper.JudgeTaskOutboxMapper;
import com.qwerlty.myojbackendquestionservice.model.entity.JudgeTaskOutbox;
import com.qwerlty.myojbackendquestionservice.mq.RabbitmqProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JudgeOutboxRelayTest {

    @Mock
    private JudgeTaskOutboxMapper judgeTaskOutboxMapper;

    @Mock
    private RabbitmqProducer rabbitmqProducer;

    @InjectMocks
    private JudgeOutboxRelay relay;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(relay, "maxRetry", 8);
        ReflectionTestUtils.setField(relay, "leaseMs", 30000L);
        ReflectionTestUtils.setField(relay, "confirmTimeoutMs", 5000L);
    }

    @Test
    void confirmedPublishIsMarkedSentWithClaimToken() {
        JudgeTaskOutbox outbox = outbox(0);
        when(judgeTaskOutboxMapper.listDispatchCandidates(10)).thenReturn(Collections.singletonList(outbox));
        when(judgeTaskOutboxMapper.claimForDispatch(eq(7L), anyString(), anyLong())).thenReturn(1);
        when(judgeTaskOutboxMapper.markSent(eq(7L), anyString())).thenReturn(1);

        assertEquals(1, relay.dispatchDue(10));

        ArgumentCaptor<String> claimToken = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sentToken = ArgumentCaptor.forClass(String.class);
        verify(judgeTaskOutboxMapper).claimForDispatch(eq(7L), claimToken.capture(), anyLong());
        verify(judgeTaskOutboxMapper).markSent(eq(7L), sentToken.capture());
        assertFalse(claimToken.getValue().isEmpty());
        assertEquals(claimToken.getValue(), sentToken.getValue());
    }

    @Test
    void publishFailureReturnsSameLeaseToPending() {
        JudgeTaskOutbox outbox = outbox(2);
        when(judgeTaskOutboxMapper.listDispatchCandidates(10)).thenReturn(Collections.singletonList(outbox));
        when(judgeTaskOutboxMapper.claimForDispatch(eq(7L), anyString(), anyLong())).thenReturn(1);
        when(judgeTaskOutboxMapper.markRetryOrDead(eq(7L), anyString(), anyInt(), anyInt(),
                any(Date.class), anyString())).thenReturn(1);
        doThrow(new IllegalStateException("broker nack")).when(rabbitmqProducer)
                .sendAndConfirm(anyString(), anyString(), anyString(), anyString(), anyLong());

        relay.dispatchDue(10);

        ArgumentCaptor<String> claimToken = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> retryToken = ArgumentCaptor.forClass(String.class);
        verify(judgeTaskOutboxMapper).claimForDispatch(eq(7L), claimToken.capture(), anyLong());
        verify(judgeTaskOutboxMapper).markRetryOrDead(eq(7L), retryToken.capture(),
                eq(JudgeTaskOutbox.STATUS_PENDING), eq(3), any(Date.class), eq("broker nack"));
        assertEquals(claimToken.getValue(), retryToken.getValue());
    }

    private JudgeTaskOutbox outbox(int retryCount) {
        JudgeTaskOutbox outbox = new JudgeTaskOutbox();
        outbox.setId(7L);
        outbox.setQuestionSubmitId(10L);
        outbox.setEventId("event-7");
        outbox.setPayload("{}");
        outbox.setRetryCount(retryCount);
        return outbox;
    }
}

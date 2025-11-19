package com.qwerlty.myojbackendaiservice.job;

import com.qwerlty.myojbackendaiservice.mapper.AiFeedbackTaskMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiFeedbackTask;
import com.qwerlty.myojbackendaiservice.model.enums.AiFeedbackStatusEnum;
import com.qwerlty.myojbackendaiservice.mq.AiFeedbackProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTaskDispatchJobTest {

    @Test
    void publisherFailureReturnsTaskToPendingWithRetry() throws Exception {
        AiFeedbackTaskMapper mapper = mock(AiFeedbackTaskMapper.class);
        AiFeedbackProducer producer = mock(AiFeedbackProducer.class);
        AiFeedbackTask task = new AiFeedbackTask();
        task.setId(21L);
        task.setDispatchRetryCount(0);
        when(mapper.listDispatchCandidates(20)).thenReturn(List.of(task));
        when(mapper.claimForDispatch(21L)).thenReturn(1);
        doThrow(new IllegalStateException("confirm failed")).when(producer).sendAndAwaitConfirm(21L);

        new AiTaskDispatchJob(mapper, producer, new SimpleMeterRegistry(), 8).dispatch();

        ArgumentCaptor<Date> retryTime = ArgumentCaptor.forClass(Date.class);
        verify(mapper).markDispatchFailure(
                eq(21L),
                eq(AiFeedbackStatusEnum.PENDING.getValue()),
                eq(1),
                retryTime.capture(),
                eq("MQ_PUBLISH_FAILED"),
                eq("confirm failed"));
        assertThat(retryTime.getValue()).isAfter(new Date());
    }
}

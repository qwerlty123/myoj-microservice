package com.qwerlty.myojbackendaiservice.mq;

import com.qwerlty.myojbackendaiservice.mapper.AiFeedbackTaskMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiFeedbackTask;
import com.qwerlty.myojbackendaiservice.model.enums.AiFeedbackStatusEnum;
import com.qwerlty.myojbackendaiservice.service.AiFeedbackService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiFeedbackConsumerTest {

    @Test
    void duplicateCompletedMessageIsAcknowledgedWithoutExecution() throws Exception {
        AiFeedbackTaskMapper mapper = mock(AiFeedbackTaskMapper.class);
        AiFeedbackService service = mock(AiFeedbackService.class);
        Channel channel = mock(Channel.class);
        AiFeedbackTask completed = new AiFeedbackTask();
        completed.setStatus(AiFeedbackStatusEnum.SUCCESS.getValue());
        when(mapper.claimForExecution(12L)).thenReturn(0);
        when(mapper.selectById(12L)).thenReturn(completed);

        new AiFeedbackConsumer(mapper, service).consume("12", channel, 99L);

        verify(channel).basicAck(99L, false);
    }

    @Test
    void messageArrivingBeforeQueuedCommitIsRequeued() throws Exception {
        AiFeedbackTaskMapper mapper = mock(AiFeedbackTaskMapper.class);
        AiFeedbackService service = mock(AiFeedbackService.class);
        Channel channel = mock(Channel.class);
        AiFeedbackTask dispatching = new AiFeedbackTask();
        dispatching.setStatus(AiFeedbackStatusEnum.DISPATCHING.getValue());
        when(mapper.claimForExecution(13L)).thenReturn(0);
        when(mapper.selectById(13L)).thenReturn(dispatching);

        new AiFeedbackConsumer(mapper, service).consume("13", channel, 100L);

        verify(channel).basicNack(100L, false, true);
    }

    @Test
    void claimedMessageExecutesThenAcknowledges() throws Exception {
        AiFeedbackTaskMapper mapper = mock(AiFeedbackTaskMapper.class);
        AiFeedbackService service = mock(AiFeedbackService.class);
        Channel channel = mock(Channel.class);
        when(mapper.claimForExecution(14L)).thenReturn(1);

        new AiFeedbackConsumer(mapper, service).consume("14", channel, 101L);

        verify(service).executeTask(14L);
        verify(channel).basicAck(101L, false);
    }
}

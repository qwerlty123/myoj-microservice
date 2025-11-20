package com.qwerlty.myojbackendquestionservice.mq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RabbitmqProducerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private RabbitmqProducer producer;

    @Test
    void waitsForAckAndPublishesPersistentMandatoryMessage() {
        producer.configurePublisher();
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().set(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq("exchange"), eq("routing"),
                any(Message.class), any(CorrelationData.class));

        producer.sendAndConfirm("exchange", "routing", "{\"submissionId\":10}", "event-10", 1000L);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).setMandatory(true);
        verify(rabbitTemplate).convertAndSend(eq("exchange"), eq("routing"),
                messageCaptor.capture(), any(CorrelationData.class));
        assertEquals("event-10", messageCaptor.getValue().getMessageProperties().getMessageId());
        assertEquals(MessageDeliveryMode.PERSISTENT,
                messageCaptor.getValue().getMessageProperties().getDeliveryMode());
    }

    @Test
    void ackedButReturnedMessageIsStillAFailure() {
        producer.configurePublisher();
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "exchange", "routing"));
            correlationData.getFuture().set(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq("exchange"), eq("routing"),
                any(Message.class), any(CorrelationData.class));

        assertThrows(IllegalStateException.class,
                () -> producer.sendAndConfirm("exchange", "routing", "{}", "event-11", 1000L));
    }
}

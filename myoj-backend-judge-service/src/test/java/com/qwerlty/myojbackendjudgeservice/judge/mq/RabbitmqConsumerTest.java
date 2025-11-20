package com.qwerlty.myojbackendjudgeservice.judge.mq;

import com.qwerlty.myojbackendjudgeservice.judge.JudgeService;
import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RabbitmqConsumerTest {

    @Mock
    private JudgeService judgeService;

    @InjectMocks
    private RabbitmqConsumer consumer;

    @Test
    void delegatesVersionedMessage() {
        consumer.receiveMessage(message("{\"messageId\":\"event-1\",\"eventType\":\"JUDGE_REQUESTED\","
                + "\"schemaVersion\":1,\"submissionId\":42,\"judgeAttempt\":3}", "event-1"));

        ArgumentCaptor<JudgeTaskMessage> captor = ArgumentCaptor.forClass(JudgeTaskMessage.class);
        verify(judgeService).doJudge(captor.capture());
        assertEquals(42L, captor.getValue().getSubmissionId());
        assertEquals(3, captor.getValue().getJudgeAttempt());
    }

    @Test
    void acceptsLegacyNumericMessageAsAttemptOne() {
        consumer.receiveMessage(message("42", null));

        ArgumentCaptor<JudgeTaskMessage> captor = ArgumentCaptor.forClass(JudgeTaskMessage.class);
        verify(judgeService).doJudge(captor.capture());
        assertEquals(1, captor.getValue().getJudgeAttempt());
    }

    @Test
    void rejectsMalformedMessageWithoutRequeue() {
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> consumer.receiveMessage(message("not-a-submission", null)));
    }

    @Test
    void rejectsMismatchedAmqpMessageId() {
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> consumer.receiveMessage(message(
                        "{\"messageId\":\"event-1\",\"eventType\":\"JUDGE_REQUESTED\","
                                + "\"schemaVersion\":1,\"submissionId\":42,\"judgeAttempt\":3}",
                        "different-event")));
    }

    private Message message(String payload, String messageId) {
        MessageBuilder builder = MessageBuilder.withBody(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (messageId != null) {
            builder.setMessageId(messageId);
        }
        return builder.build();
    }
}

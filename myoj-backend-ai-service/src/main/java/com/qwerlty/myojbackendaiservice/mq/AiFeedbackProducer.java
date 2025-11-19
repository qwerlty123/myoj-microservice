package com.qwerlty.myojbackendaiservice.mq;

import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class AiFeedbackProducer {

    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMs;

    public AiFeedbackProducer(RabbitTemplate rabbitTemplate,
                              @Value("${myoj.ai.task.publisher-confirm-timeout-ms:3000}") long confirmTimeoutMs) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.rabbitTemplate.setMandatory(true);
    }

    public void sendAndAwaitConfirm(Long taskId) throws Exception {
        CorrelationData correlationData = new CorrelationData(String.valueOf(taskId));
        rabbitTemplate.convertAndSend(
                AiMqConstants.EXCHANGE,
                AiMqConstants.ROUTING_KEY,
                String.valueOf(taskId),
                correlationData);
        CorrelationData.Confirm confirm = correlationData.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        if (!confirm.isAck()) {
            throw new IllegalStateException("RabbitMQ publisher confirm rejected: " + confirm.getReason());
        }
        if (correlationData.getReturned() != null) {
            throw new IllegalStateException("RabbitMQ message was returned: "
                    + correlationData.getReturned().getReplyText());
        }
    }
}

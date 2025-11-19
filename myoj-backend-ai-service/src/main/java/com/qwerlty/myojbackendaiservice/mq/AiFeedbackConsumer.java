package com.qwerlty.myojbackendaiservice.mq;

import com.qwerlty.myojbackendaiservice.mapper.AiFeedbackTaskMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiFeedbackTask;
import com.qwerlty.myojbackendaiservice.model.enums.AiFeedbackStatusEnum;
import com.qwerlty.myojbackendaiservice.service.AiFeedbackService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
public class AiFeedbackConsumer {

    private final AiFeedbackTaskMapper taskMapper;
    private final AiFeedbackService feedbackService;

    public AiFeedbackConsumer(AiFeedbackTaskMapper taskMapper, AiFeedbackService feedbackService) {
        this.taskMapper = taskMapper;
        this.feedbackService = feedbackService;
    }

    @RabbitListener(queues = AiMqConstants.QUEUE)
    public void consume(String message,
                        Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        Long taskId;
        try {
            taskId = Long.valueOf(message);
        } catch (NumberFormatException exception) {
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        if (taskMapper.claimForExecution(taskId) <= 0) {
            AiFeedbackTask task = taskMapper.selectById(taskId);
            if (task != null && (AiFeedbackStatusEnum.PENDING.getValue() == task.getStatus()
                    || AiFeedbackStatusEnum.DISPATCHING.getValue() == task.getStatus())) {
                channel.basicNack(deliveryTag, false, true);
            } else {
                channel.basicAck(deliveryTag, false);
            }
            return;
        }

        try {
            feedbackService.executeTask(taskId);
            channel.basicAck(deliveryTag, false);
        } catch (Throwable throwable) {
            log.error("Unexpected AI task consumer failure, taskId={}", taskId, throwable);
            // RUNNING 任务由补偿任务恢复；当前消息进入死信，避免立即重放形成风暴。
            channel.basicNack(deliveryTag, false, false);
        }
    }
}

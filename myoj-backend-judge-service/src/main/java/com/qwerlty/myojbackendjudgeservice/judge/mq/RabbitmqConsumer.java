package com.qwerlty.myojbackendjudgeservice.judge.mq;

import cn.hutool.json.JSONUtil;
import com.qwerlty.myojbackendcommon.common.ErrorCode;
import com.qwerlty.myojbackendcommon.constant.MqConstant;
import com.qwerlty.myojbackendcommon.exception.BusinessException;
import com.qwerlty.myojbackendjudgeservice.judge.JudgeService;
import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class RabbitmqConsumer {

    @Resource
    private JudgeService judgeService;

    /**
     * AUTO is Spring container-managed acknowledgement, not RabbitMQ native auto-ack.
     */
    @RabbitListener(queues = MqConstant.NORMAL_QUEUE_NAME, ackMode = "AUTO")
    public void receiveMessage(Message amqpMessage) {
        String payload = new String(amqpMessage.getBody(), StandardCharsets.UTF_8);
        JudgeTaskMessage message = parse(payload);
        String amqpMessageId = amqpMessage.getMessageProperties().getMessageId();
        if (StringUtils.isNotBlank(amqpMessageId) && !amqpMessageId.equals(message.getMessageId())) {
            throw new AmqpRejectAndDontRequeueException("AMQP message_id does not match event messageId");
        }
        log.info("receive judge event, messageId={}, submitId={}, attempt={}",
                message.getMessageId(), message.getSubmissionId(), message.getJudgeAttempt());
        try {
            judgeService.doJudge(message);
        } catch (BusinessException e) {
            if (e.getCode() == ErrorCode.NOT_FOUND_ERROR.getCode()
                    || e.getCode() == ErrorCode.PARAMS_ERROR.getCode()) {
                throw new AmqpRejectAndDontRequeueException("invalid judge task", e);
            }
            throw e;
        }
    }

    private JudgeTaskMessage parse(String payload) {
        try {
            String value = StringUtils.trimToEmpty(payload);
            JudgeTaskMessage message;
            if (value.startsWith("{")) {
                message = JSONUtil.toBean(value, JudgeTaskMessage.class);
            } else {
                // Rolling-upgrade compatibility. Legacy numeric messages always belong to attempt 1.
                Long submissionId = Long.valueOf(value);
                message = JudgeTaskMessage.builder()
                        .messageId("legacy-" + submissionId)
                        .eventType(JudgeTaskMessage.EVENT_TYPE)
                        .schemaVersion(JudgeTaskMessage.SCHEMA_VERSION)
                        .submissionId(submissionId)
                        .judgeAttempt(1)
                        .build();
            }
            validate(message);
            return message;
        } catch (Exception e) {
            if (e instanceof AmqpRejectAndDontRequeueException) {
                throw (AmqpRejectAndDontRequeueException) e;
            }
            throw new AmqpRejectAndDontRequeueException("malformed judge message", e);
        }
    }

    private void validate(JudgeTaskMessage message) {
        boolean invalid = message == null
                || StringUtils.isBlank(message.getMessageId())
                || !JudgeTaskMessage.EVENT_TYPE.equals(message.getEventType())
                || !Integer.valueOf(JudgeTaskMessage.SCHEMA_VERSION).equals(message.getSchemaVersion())
                || message.getSubmissionId() == null
                || message.getSubmissionId() <= 0
                || message.getJudgeAttempt() == null
                || message.getJudgeAttempt() <= 0;
        if (invalid) {
            throw new AmqpRejectAndDontRequeueException("unsupported judge message");
        }
    }
}

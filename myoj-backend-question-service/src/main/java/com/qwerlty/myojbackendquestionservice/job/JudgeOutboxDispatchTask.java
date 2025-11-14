package com.qwerlty.myojbackendquestionservice.job;

import com.qwerlty.myojbackendcommon.constant.MqConstant;
import com.qwerlty.myojbackendquestionservice.mapper.JudgeTaskOutboxMapper;
import com.qwerlty.myojbackendquestionservice.model.entity.JudgeTaskOutbox;
import com.qwerlty.myojbackendquestionservice.mq.RabbitmqProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

/**
 * Outbox 投递任务：从本地表重试发送判题消息，保证提交记录与 MQ 最终一致。
 */
@Slf4j
@Component
public class JudgeOutboxDispatchTask {

    private static final int STATUS_PENDING = 0;
    private static final int STATUS_STOP = 2;

    @Resource
    private JudgeTaskOutboxMapper judgeTaskOutboxMapper;

    @Resource
    private RabbitmqProducer rabbitmqProducer;

    @Value("${judge-consistency.outbox.batch-size:20}")
    private int batchSize;

    @Value("${judge-consistency.outbox.max-retry:8}")
    private int maxRetry;

    @Scheduled(fixedDelayString = "${judge-consistency.outbox.dispatch-interval-ms:3000}")
    public void dispatch() {
        // 宕机恢复：释放长时间停留在“投递中”的记录
        judgeTaskOutboxMapper.releaseStaleDispatching(new Date(System.currentTimeMillis() - 30000));
        List<JudgeTaskOutbox> candidates = judgeTaskOutboxMapper.listDispatchCandidates(batchSize);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (JudgeTaskOutbox outbox : candidates) {
            // 多实例下通过 CAS 认领，避免重复发送
            int claimed = judgeTaskOutboxMapper.claimForDispatch(outbox.getId());
            if (claimed <= 0) {
                continue;
            }
            try {
                rabbitmqProducer.sendMessage(MqConstant.EXCHANGE_NAME, MqConstant.NORMAL_ROUTING_KEY, outbox.getPayload());
                judgeTaskOutboxMapper.markSent(outbox.getId());
            } catch (Exception e) {
                int retry = (outbox.getRetryCount() == null ? 0 : outbox.getRetryCount()) + 1;
                boolean shouldStop = retry >= maxRetry;
                Date nextRetry = new Date(System.currentTimeMillis() + backoffMs(retry));
                int status = shouldStop ? STATUS_STOP : STATUS_PENDING;
                judgeTaskOutboxMapper.markRetryOrStop(outbox.getId(), status, retry, nextRetry, trim(e.getMessage()));
                log.error("outbox dispatch failed, outboxId={}, submitId={}, retry={}",
                        outbox.getId(), outbox.getQuestionSubmitId(), retry, e);
            }
        }
    }

    private long backoffMs(int retry) {
        long base = 2000L;
        long max = 60000L;
        long value = base << Math.min(retry, 5);
        return Math.min(value, max);
    }

    private String trim(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}


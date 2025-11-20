package com.qwerlty.myojbackendquestionservice.service;

import com.qwerlty.myojbackendcommon.constant.MqConstant;
import com.qwerlty.myojbackendquestionservice.mapper.JudgeTaskOutboxMapper;
import com.qwerlty.myojbackendquestionservice.model.entity.JudgeTaskOutbox;
import com.qwerlty.myojbackendquestionservice.mq.RabbitmqProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Reliable relay for judge outbox events. It owns leases, publisher confirms and retry backoff.
 */
@Slf4j
@Service
public class JudgeOutboxRelay {

    @Resource
    private JudgeTaskOutboxMapper judgeTaskOutboxMapper;

    @Resource
    private RabbitmqProducer rabbitmqProducer;

    @Value("${judge-consistency.outbox.max-retry:8}")
    private int maxRetry;

    @Value("${judge-consistency.outbox.lease-ms:30000}")
    private long leaseMs;

    @Value("${judge-consistency.outbox.confirm-timeout-ms:5000}")
    private long confirmTimeoutMs;

    public int dispatchDue(int limit) {
        judgeTaskOutboxMapper.releaseExpiredLeases();
        List<JudgeTaskOutbox> candidates = judgeTaskOutboxMapper.listDispatchCandidates(limit);
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        int claimedCount = 0;
        for (JudgeTaskOutbox outbox : candidates) {
            String lockToken = UUID.randomUUID().toString();
            long effectiveLeaseMs = Math.max(leaseMs, confirmTimeoutMs + 1000L);
            if (judgeTaskOutboxMapper.claimForDispatch(outbox.getId(), lockToken, effectiveLeaseMs) <= 0) {
                continue;
            }
            claimedCount++;
            dispatchClaimed(outbox, lockToken);
        }
        return claimedCount;
    }

    private void dispatchClaimed(JudgeTaskOutbox outbox, String lockToken) {
        try {
            rabbitmqProducer.sendAndConfirm(
                    MqConstant.EXCHANGE_NAME,
                    MqConstant.NORMAL_ROUTING_KEY,
                    outbox.getPayload(),
                    outbox.getEventId(),
                    confirmTimeoutMs);
            int updated = judgeTaskOutboxMapper.markSent(outbox.getId(), lockToken);
            if (updated <= 0) {
                log.warn("publisher confirmed but outbox lease was lost, outboxId={}, eventId={}",
                        outbox.getId(), outbox.getEventId());
            }
        } catch (Exception e) {
            int retry = (outbox.getRetryCount() == null ? 0 : outbox.getRetryCount()) + 1;
            boolean dead = retry >= maxRetry;
            int status = dead ? JudgeTaskOutbox.STATUS_DEAD : JudgeTaskOutbox.STATUS_PENDING;
            Date nextRetry = new Date(System.currentTimeMillis() + backoffMs(retry));
            int updated = judgeTaskOutboxMapper.markRetryOrDead(
                    outbox.getId(), lockToken, status, retry, nextRetry, trim(e.getMessage()));
            if (updated > 0) {
                log.error("outbox dispatch failed, outboxId={}, submitId={}, retry={}, dead={}",
                        outbox.getId(), outbox.getQuestionSubmitId(), retry, dead, e);
            } else {
                log.warn("ignore failure from expired outbox lease, outboxId={}, eventId={}",
                        outbox.getId(), outbox.getEventId());
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
        return message.length() > 1024 ? message.substring(0, 1024) : message;
    }
}

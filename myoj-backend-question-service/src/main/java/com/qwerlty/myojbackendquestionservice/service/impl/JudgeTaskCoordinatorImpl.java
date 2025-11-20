package com.qwerlty.myojbackendquestionservice.service.impl;

import cn.hutool.json.JSONUtil;
import com.qwerlty.myojbackendmodel.model.codesandbox.JudgeInfo;
import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskCompleteRequest;
import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskMessage;
import com.qwerlty.myojbackendmodel.model.enums.JudgeInfoMessageEnum;
import com.qwerlty.myojbackendmodel.model.enums.QuestionSubmitStatusEnum;
import com.qwerlty.myojbackendquestionservice.mapper.JudgeTaskOutboxMapper;
import com.qwerlty.myojbackendquestionservice.mapper.QuestionSubmitMapper;
import com.qwerlty.myojbackendquestionservice.model.entity.JudgeTaskOutbox;
import com.qwerlty.myojbackendquestionservice.service.JudgeTaskCoordinator;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Date;
import java.util.UUID;

@Service
public class JudgeTaskCoordinatorImpl implements JudgeTaskCoordinator {

    @Resource
    private QuestionSubmitMapper questionSubmitMapper;

    @Resource
    private JudgeTaskOutboxMapper judgeTaskOutboxMapper;

    @Value("${judge-consistency.submission.max-retry:3}")
    private int maxJudgeRetry;

    @Value("${judge-consistency.submission.retry-backoff-ms:5000}")
    private long retryBackoffMs;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void createInitialTask(Long submissionId, Integer judgeAttempt) {
        insertOutbox(submissionId, judgeAttempt, new Date());
    }

    @Override
    public boolean claim(Long submissionId, Integer judgeAttempt) {
        return questionSubmitMapper.claimForJudge(submissionId, judgeAttempt) > 0;
    }

    @Override
    public boolean complete(JudgeTaskCompleteRequest request) {
        Integer status = request.getStatus();
        if (!QuestionSubmitStatusEnum.SUCCEED.getValue().equals(status)
                && !QuestionSubmitStatusEnum.FAILED.getValue().equals(status)) {
            return false;
        }
        return questionSubmitMapper.finishFromRunning(
                request.getSubmissionId(),
                request.getJudgeAttempt(),
                status,
                request.getJudgeInfo(),
                request.getLastError()) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean scheduleRetry(Long submissionId, Integer judgeAttempt, String lastError) {
        Date nextRetryTime = new Date(System.currentTimeMillis() + retryDelayMs(judgeAttempt));
        int retried = questionSubmitMapper.retryRunningAsWaiting(
                submissionId,
                judgeAttempt,
                maxJudgeRetry,
                nextRetryTime,
                trim(lastError));
        if (retried > 0) {
            insertOutbox(submissionId, judgeAttempt + 1, nextRetryTime);
            return true;
        }

        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue());
        return questionSubmitMapper.markFailedAfterRetryExhausted(
                submissionId,
                judgeAttempt,
                maxJudgeRetry,
                JSONUtil.toJsonStr(judgeInfo),
                trim(StringUtils.defaultIfBlank(lastError, "judge retry exhausted"))) > 0;
    }

    private void insertOutbox(Long submissionId, Integer judgeAttempt, Date nextRetryTime) {
        Date createdAt = new Date();
        String eventId = UUID.randomUUID().toString();
        JudgeTaskMessage message = JudgeTaskMessage.builder()
                .messageId(eventId)
                .eventType(JudgeTaskMessage.EVENT_TYPE)
                .schemaVersion(JudgeTaskMessage.SCHEMA_VERSION)
                .submissionId(submissionId)
                .judgeAttempt(judgeAttempt)
                .createdAt(createdAt)
                .build();

        JudgeTaskOutbox outbox = new JudgeTaskOutbox();
        outbox.setQuestionSubmitId(submissionId);
        outbox.setEventId(eventId);
        outbox.setEventType(JudgeTaskMessage.EVENT_TYPE);
        outbox.setSchemaVersion(JudgeTaskMessage.SCHEMA_VERSION);
        outbox.setJudgeAttempt(judgeAttempt);
        outbox.setPayload(JSONUtil.toJsonStr(message));
        outbox.setStatus(JudgeTaskOutbox.STATUS_PENDING);
        outbox.setRetryCount(0);
        outbox.setNextRetryTime(nextRetryTime);
        if (judgeTaskOutboxMapper.insert(outbox) <= 0) {
            throw new IllegalStateException("failed to insert judge outbox event");
        }
    }

    private long retryDelayMs(Integer judgeAttempt) {
        int completedRetries = Math.max(0, judgeAttempt - 1);
        long multiplier = 1L << Math.min(completedRetries, 5);
        return Math.min(retryBackoffMs * multiplier, 60000L);
    }

    private String trim(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 1024 ? message.substring(0, 1024) : message;
    }
}

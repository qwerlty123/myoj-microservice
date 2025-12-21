package com.qwerlty.myojbackendquestionservice.job;

import cn.hutool.json.JSONUtil;
import com.qwerlty.myojbackendmodel.model.codesandbox.JudgeInfo;
import com.qwerlty.myojbackendmodel.model.entity.QuestionSubmit;
import com.qwerlty.myojbackendmodel.model.enums.JudgeInfoMessageEnum;
import com.qwerlty.myojbackendquestionservice.mapper.JudgeTaskOutboxMapper;
import com.qwerlty.myojbackendquestionservice.model.entity.JudgeTaskOutbox;
import com.qwerlty.myojbackendquestionservice.service.QuestionSubmitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

/**
 * 判题一致性补偿：处理超时 RUNNING 与滞留 WAITING，确保任务最终收敛到终态。
 */
@Slf4j
@Component
public class JudgeConsistencyCompensationTask {

    @Resource
    private QuestionSubmitService questionSubmitService;

    @Resource
    private JudgeTaskOutboxMapper judgeTaskOutboxMapper;

    @Value("${judge-consistency.submission.batch-size:50}")
    private int batchSize;

    @Value("${judge-consistency.submission.max-retry:3}")
    private int maxJudgeRetry;

    @Value("${judge-consistency.submission.running-timeout-ms:180000}")
    private long runningTimeoutMs;

    @Value("${judge-consistency.submission.waiting-timeout-ms:60000}")
    private long waitingTimeoutMs;

    @Scheduled(fixedDelayString = "${judge-consistency.submission.compensate-interval-ms:15000}")
    public void compensate() {
        compensateTimeoutRunning();
        compensateStuckWaiting();
    }

    private void compensateTimeoutRunning() {
        Date deadline = new Date(System.currentTimeMillis() - runningTimeoutMs);
        List<QuestionSubmit> timeoutList = questionSubmitService.listTimeoutRunning(deadline, batchSize);
        for (QuestionSubmit submit : timeoutList) {
            int retry = submit.getRetryCount() == null ? 0 : submit.getRetryCount();
            if (retry >= maxJudgeRetry) {
                String judgeInfo = buildTimeoutJudgeInfo();
                boolean failed = questionSubmitService.markFailedIfUnfinished(
                        submit.getId(), judgeInfo, "judge timeout exceeded max retry");
                if (failed) {
                    log.warn("mark failed by compensation, submitId={}", submit.getId());
                }
                continue;
            }
            boolean reset = questionSubmitService.retryRunningAsWaiting(
                    submit.getId(), new Date(), "judge timeout, requeue by compensation");
            if (reset) {
                enqueueOutbox(submit.getId());
                log.warn("requeue timeout running submit, submitId={}, retry={}", submit.getId(), retry + 1);
            }
        }
    }

    private void compensateStuckWaiting() {
        Date deadline = new Date(System.currentTimeMillis() - waitingTimeoutMs);
        List<QuestionSubmit> waitingList = questionSubmitService.listStuckWaiting(deadline, batchSize);
        for (QuestionSubmit submit : waitingList) {
            int active = judgeTaskOutboxMapper.countActiveDispatchBySubmitId(submit.getId());
            if (active > 0) {
                continue;
            }
            enqueueOutbox(submit.getId());
            log.warn("recreate outbox for stuck waiting submit, submitId={}", submit.getId());
        }
    }

    private void enqueueOutbox(Long submitId) {
        JudgeTaskOutbox outbox = new JudgeTaskOutbox();
        outbox.setQuestionSubmitId(submitId);
        outbox.setPayload(String.valueOf(submitId));
        outbox.setStatus(0);
        outbox.setRetryCount(0);
        outbox.setNextRetryTime(new Date());
        judgeTaskOutboxMapper.insert(outbox);
    }

    private String buildTimeoutJudgeInfo() {
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue());
        return JSONUtil.toJsonStr(judgeInfo);
    }
}


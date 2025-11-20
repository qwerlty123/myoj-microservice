package com.qwerlty.myojbackendquestionservice.job;

import com.qwerlty.myojbackendmodel.model.entity.QuestionSubmit;
import com.qwerlty.myojbackendquestionservice.service.JudgeTaskCoordinator;
import com.qwerlty.myojbackendquestionservice.service.QuestionSubmitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

/**
 * 判题一致性补偿：只恢复执行租约超时的 RUNNING，不对队列中的 WAITING 盲目重投。
 */
@Slf4j
@Component
public class JudgeConsistencyCompensationTask {

    @Resource
    private QuestionSubmitService questionSubmitService;

    @Resource
    private JudgeTaskCoordinator judgeTaskCoordinator;

    @Value("${judge-consistency.submission.batch-size:50}")
    private int batchSize;

    @Value("${judge-consistency.submission.running-timeout-ms:180000}")
    private long runningTimeoutMs;

    @Scheduled(fixedDelayString = "${judge-consistency.submission.compensate-interval-ms:15000}")
    public void compensate() {
        compensateTimeoutRunning();
    }

    private void compensateTimeoutRunning() {
        Date deadline = new Date(System.currentTimeMillis() - runningTimeoutMs);
        List<QuestionSubmit> timeoutList = questionSubmitService.listTimeoutRunning(deadline, batchSize);
        for (QuestionSubmit submit : timeoutList) {
            boolean transitioned = judgeTaskCoordinator.scheduleRetry(
                    submit.getId(), submit.getJudgeAttempt(), "judge timeout, schedule next attempt");
            if (transitioned) {
                log.warn("recover timeout running submit, submitId={}, attempt={}",
                        submit.getId(), submit.getJudgeAttempt());
            }
        }
    }
}

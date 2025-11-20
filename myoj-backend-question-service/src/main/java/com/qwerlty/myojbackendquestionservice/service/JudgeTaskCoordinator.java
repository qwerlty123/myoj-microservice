package com.qwerlty.myojbackendquestionservice.service;

import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskCompleteRequest;

/**
 * Owns the judge execution state machine and the transaction that schedules its next attempt.
 */
public interface JudgeTaskCoordinator {

    void createInitialTask(Long submissionId, Integer judgeAttempt);

    boolean claim(Long submissionId, Integer judgeAttempt);

    boolean complete(JudgeTaskCompleteRequest request);

    boolean scheduleRetry(Long submissionId, Integer judgeAttempt, String lastError);
}

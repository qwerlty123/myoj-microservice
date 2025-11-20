package com.qwerlty.myojbackendquestionservice.job;

import com.qwerlty.myojbackendquestionservice.service.JudgeOutboxRelay;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
/**
 * Thin scheduling adapter; all dispatch semantics live in {@link JudgeOutboxRelay}.
 */
@Component
public class JudgeOutboxDispatchTask {

    @Resource
    private JudgeOutboxRelay judgeOutboxRelay;

    @Value("${judge-consistency.outbox.batch-size:20}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${judge-consistency.outbox.dispatch-interval-ms:3000}")
    public void dispatch() {
        judgeOutboxRelay.dispatchDue(batchSize);
    }
}

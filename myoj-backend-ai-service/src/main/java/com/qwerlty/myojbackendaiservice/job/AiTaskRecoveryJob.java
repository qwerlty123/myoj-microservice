package com.qwerlty.myojbackendaiservice.job;

import com.qwerlty.myojbackendaiservice.mapper.AiFeedbackTaskMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiFeedbackTask;
import com.qwerlty.myojbackendaiservice.model.enums.AiFeedbackStatusEnum;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Component
public class AiTaskRecoveryJob {

    private final AiFeedbackTaskMapper taskMapper;
    private final int maxExecuteRetry;
    private final long runningTimeoutMs;

    public AiTaskRecoveryJob(AiFeedbackTaskMapper taskMapper,
                             @Value("${myoj.ai.task.max-execute-retry:3}") int maxExecuteRetry,
                             @Value("${myoj.ai.task.running-timeout-ms:120000}") long runningTimeoutMs) {
        this.taskMapper = taskMapper;
        this.maxExecuteRetry = maxExecuteRetry;
        this.runningTimeoutMs = runningTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${myoj.ai.task.recovery-interval-ms:15000}")
    public void recover() {
        Date staleBefore = new Date(System.currentTimeMillis() - runningTimeoutMs);
        List<AiFeedbackTask> staleTasks = taskMapper.listStaleRunning(staleBefore, 20);
        for (AiFeedbackTask task : staleTasks) {
            int executeRetry = task.getExecuteRetryCount() == null ? 0 : task.getExecuteRetryCount();
            if (executeRetry >= maxExecuteRetry) {
                taskMapper.markExecutionTerminal(
                        task.getId(),
                        AiFeedbackStatusEnum.TIMEOUT.getValue(),
                        "TASK_TIMEOUT",
                        "AI 分析执行超时",
                        runningTimeoutMs);
            } else {
                taskMapper.markExecutionRetry(
                        task.getId(),
                        new Date(System.currentTimeMillis() + executeBackoffMs(executeRetry)),
                        "TASK_RECOVERED",
                        "检测到执行实例中断，任务已重新排队");
            }
        }
    }

    private long executeBackoffMs(int retry) {
        long[] values = {5_000L, 30_000L, 120_000L};
        int index = Math.max(0, Math.min(retry - 1, values.length - 1));
        return values[index];
    }
}

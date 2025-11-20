package com.qwerlty.myojbackendaiservice.job;

import com.qwerlty.myojbackendaiservice.mapper.AiFeedbackTaskMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiFeedbackTask;
import com.qwerlty.myojbackendaiservice.model.enums.AiFeedbackStatusEnum;
import com.qwerlty.myojbackendaiservice.queue.AiFeedbackStreamConsumer;
import com.qwerlty.myojbackendaiservice.queue.AiFeedbackStreamManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.Duration;

@Slf4j
@Component
public class AiTaskRecoveryJob {

    private final AiFeedbackTaskMapper taskMapper;
    private final AiFeedbackStreamManager streamManager;
    private final AiFeedbackStreamConsumer streamConsumer;
    private final int maxExecuteRetry;
    private final long runningTimeoutMs;
    private final int reclaimBatchSize;
    private final String recoveryConsumerName;

    public AiTaskRecoveryJob(AiFeedbackTaskMapper taskMapper,
                             AiFeedbackStreamManager streamManager,
                             AiFeedbackStreamConsumer streamConsumer,
                             @Value("${myoj.ai.task.max-execute-retry:3}") int maxExecuteRetry,
                             @Value("${myoj.ai.task.running-timeout-ms:120000}") long runningTimeoutMs,
                             @Value("${myoj.ai.stream.reclaim-batch-size:20}") int reclaimBatchSize,
                             @Value("${myoj.ai.stream.consumer-prefix:${HOSTNAME:ai-service}}") String consumerPrefix) {
        this.taskMapper = taskMapper;
        this.streamManager = streamManager;
        this.streamConsumer = streamConsumer;
        this.maxExecuteRetry = maxExecuteRetry;
        this.runningTimeoutMs = runningTimeoutMs;
        this.reclaimBatchSize = reclaimBatchSize;
        this.recoveryConsumerName = consumerPrefix + "-recovery";
    }

    @Scheduled(fixedDelayString = "${myoj.ai.task.recovery-interval-ms:15000}")
    public void recover() {
        try {
            List<MapRecord<String, String, String>> records = streamManager.claimStale(
                    recoveryConsumerName, Duration.ofMillis(runningTimeoutMs), reclaimBatchSize);
            records.forEach(this::recoverRecord);
        } catch (RuntimeException exception) {
            log.warn("Unable to reclaim AI Redis Stream pending messages: {}", exception.getMessage());
        }
    }

    private void recoverRecord(MapRecord<String, String, String> record) {
        Long taskId = parseTaskId(record);
        if (taskId == null) {
            streamConsumer.consume(record);
            return;
        }
        AiFeedbackTask task = taskMapper.selectById(taskId);
        if (task == null || !Integer.valueOf(AiFeedbackStatusEnum.RUNNING.getValue()).equals(task.getStatus())) {
            streamConsumer.consume(record);
            return;
        }
        int attemptCount = task.getAttemptCount() == null ? 0 : task.getAttemptCount();
        if (attemptCount >= maxExecuteRetry) {
            if (taskMapper.markExecutionTerminal(
                    taskId,
                    AiFeedbackStatusEnum.TIMEOUT.getValue(),
                    "TASK_TIMEOUT",
                    "AI 分析执行超时",
                    runningTimeoutMs) > 0) {
                streamManager.acknowledgeAndDelete(record.getId());
            }
            return;
        }
        if (taskMapper.markExecutionRetry(
                taskId,
                "TASK_RECOVERED",
                "检测到执行实例中断，任务已重新排队") > 0) {
            streamConsumer.consume(record);
        }
    }

    private Long parseTaskId(MapRecord<String, String, String> record) {
        try {
            return Long.valueOf(record.getValue().get("taskId"));
        } catch (RuntimeException exception) {
            return null;
        }
    }
}

package com.qwerlty.myojbackendaiservice.job;

import com.qwerlty.myojbackendaiservice.mapper.AiFeedbackTaskMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiFeedbackTask;
import com.qwerlty.myojbackendaiservice.model.enums.AiFeedbackStatusEnum;
import com.qwerlty.myojbackendaiservice.mq.AiFeedbackProducer;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Slf4j
@Component
public class AiTaskDispatchJob {

    private final AiFeedbackTaskMapper taskMapper;
    private final AiFeedbackProducer producer;
    private final MeterRegistry meterRegistry;
    private final int maxDispatchRetry;

    public AiTaskDispatchJob(AiFeedbackTaskMapper taskMapper,
                             AiFeedbackProducer producer,
                             MeterRegistry meterRegistry,
                             @Value("${myoj.ai.task.max-dispatch-retry:8}") int maxDispatchRetry) {
        this.taskMapper = taskMapper;
        this.producer = producer;
        this.meterRegistry = meterRegistry;
        this.maxDispatchRetry = maxDispatchRetry;
    }

    @Scheduled(fixedDelayString = "${myoj.ai.task.dispatch-interval-ms:1000}")
    public void dispatch() {
        taskMapper.releaseStaleDispatching(new Date(System.currentTimeMillis() - 30_000));
        List<AiFeedbackTask> candidates = taskMapper.listDispatchCandidates(20);
        for (AiFeedbackTask task : candidates) {
            if (taskMapper.claimForDispatch(task.getId()) <= 0) {
                continue;
            }
            try {
                producer.sendAndAwaitConfirm(task.getId());
                if (taskMapper.markQueued(task.getId()) <= 0) {
                    throw new IllegalStateException("Unable to mark AI task queued after publisher confirm");
                }
            } catch (Exception exception) {
                int retry = value(task.getDispatchRetryCount()) + 1;
                boolean stopped = retry >= maxDispatchRetry;
                int nextStatus = stopped
                        ? AiFeedbackStatusEnum.FAILED.getValue()
                        : AiFeedbackStatusEnum.PENDING.getValue();
                taskMapper.markDispatchFailure(
                        task.getId(),
                        nextStatus,
                        retry,
                        new Date(System.currentTimeMillis() + dispatchBackoffMs(retry)),
                        "MQ_PUBLISH_FAILED",
                        trim(exception.getMessage()));
                meterRegistry.counter("ai_feedback_dispatch_retry_total",
                        "outcome", stopped ? "exhausted" : "retry").increment();
                log.warn("AI task dispatch failed, taskId={}, retry={}", task.getId(), retry);
            }
        }
    }

    private int value(Integer number) {
        return number == null ? 0 : number;
    }

    private long dispatchBackoffMs(int retry) {
        return Math.min(2_000L << Math.min(retry, 5), 60_000L);
    }

    private String trim(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}

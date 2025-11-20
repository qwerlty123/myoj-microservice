package com.qwerlty.myojbackendaiservice.queue;

import com.qwerlty.myojbackendaiservice.mapper.AiFeedbackTaskMapper;
import com.qwerlty.myojbackendaiservice.service.AiFeedbackService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AiFeedbackStreamConsumer {

    private final AiFeedbackTaskMapper taskMapper;
    private final AiFeedbackService feedbackService;
    private final AiFeedbackStreamManager streamManager;
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private final MeterRegistry meterRegistry;
    private final String consumerPrefix;
    private final int concurrency;

    public AiFeedbackStreamConsumer(
            AiFeedbackTaskMapper taskMapper,
            AiFeedbackService feedbackService,
            AiFeedbackStreamManager streamManager,
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
            MeterRegistry meterRegistry,
            @Value("${myoj.ai.stream.consumer-prefix:${HOSTNAME:ai-service}}") String consumerPrefix,
            @Value("${myoj.ai.stream.concurrency:2}") int concurrency) {
        this.taskMapper = taskMapper;
        this.feedbackService = feedbackService;
        this.streamManager = streamManager;
        this.container = container;
        this.meterRegistry = meterRegistry;
        this.consumerPrefix = consumerPrefix;
        this.concurrency = Math.max(1, concurrency);
    }

    @PostConstruct
    public void subscribe() {
        streamManager.ensureGroup();
        for (int index = 0; index < concurrency; index++) {
            container.receive(
                    Consumer.from(streamManager.getGroup(), consumerPrefix + "-" + index),
                    StreamOffset.create(streamManager.getStreamKey(), ReadOffset.lastConsumed()),
                    this::consume);
        }
        container.start();
    }

    public void consume(MapRecord<String, String, String> record) {
        String rawTaskId = record.getValue().get("taskId");
        Long taskId;
        try {
            taskId = Long.valueOf(rawTaskId);
        } catch (RuntimeException exception) {
            meterRegistry.counter("ai_feedback_stream_consumed_total", "outcome", "invalid").increment();
            streamManager.acknowledgeAndDelete(record.getId());
            return;
        }

        if (taskMapper.claimForExecution(taskId) <= 0) {
            meterRegistry.counter("ai_feedback_stream_consumed_total", "outcome", "duplicate").increment();
            streamManager.acknowledgeAndDelete(record.getId());
            return;
        }

        try {
            feedbackService.executeTask(taskId);
            streamManager.acknowledgeAndDelete(record.getId());
            meterRegistry.counter("ai_feedback_stream_consumed_total", "outcome", "handled").increment();
        } catch (Throwable throwable) {
            // 不 ACK；由 Pending reclaim 和数据库 RUNNING 补偿恢复。
            meterRegistry.counter("ai_feedback_stream_consumed_total", "outcome", "unexpected").increment();
            log.error("Unexpected AI Redis Stream consumer failure, taskId={}, errorType={}",
                    taskId, throwable.getClass().getSimpleName());
        }
    }
}

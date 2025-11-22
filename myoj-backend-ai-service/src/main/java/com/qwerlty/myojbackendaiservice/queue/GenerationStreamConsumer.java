package com.qwerlty.myojbackendaiservice.queue;

import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.service.GenerationTaskService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GenerationStreamConsumer {
    private final AiProblemGenerationTaskMapper taskMapper;
    private final GenerationTaskService taskService;
    private final GenerationStreamManager streamManager;
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private final MeterRegistry meterRegistry;
    private final String consumerPrefix;
    private final int concurrency;

    public GenerationStreamConsumer(
            AiProblemGenerationTaskMapper taskMapper,
            GenerationTaskService taskService,
            GenerationStreamManager streamManager,
            @Qualifier("generationStreamListenerContainer")
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
            MeterRegistry meterRegistry,
            @Value("${myoj.ai.generation.stream.consumer-prefix:${HOSTNAME:ai-generation}}") String consumerPrefix,
            @Value("${myoj.ai.generation.concurrency:2}") int concurrency) {
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.streamManager = streamManager;
        this.container = container;
        this.meterRegistry = meterRegistry;
        this.consumerPrefix = consumerPrefix;
        this.concurrency = Math.max(1, concurrency);
    }

    @PostConstruct
    public void subscribe() {
        log.info("[AI_GENERATION] starting stream consumers stream={} group={} consumerPrefix={} concurrency={}",
                streamManager.getStreamKey(), streamManager.getGroup(), consumerPrefix, concurrency);
        streamManager.ensureGroup();
        for (int index = 0; index < concurrency; index++) {
            container.receive(
                    Consumer.from(streamManager.getGroup(), consumerPrefix + "-" + index),
                    StreamOffset.create(streamManager.getStreamKey(), ReadOffset.lastConsumed()),
                    this::consume);
        }
        container.start();
        log.info("[AI_GENERATION] stream consumers started stream={} group={}",
                streamManager.getStreamKey(), streamManager.getGroup());
    }

    public void consume(MapRecord<String, String, String> record) {
        log.info("[AI_GENERATION] stream record received recordId={}", record.getId().getValue());
        Long taskId;
        try {
            taskId = Long.valueOf(record.getValue().get("taskId"));
        } catch (RuntimeException exception) {
            meterRegistry.counter("ai_generation_stream_total", "outcome", "invalid").increment();
            log.warn("[AI_GENERATION] invalid stream record discarded recordId={} errorType={}",
                    record.getId().getValue(), exception.getClass().getSimpleName());
            streamManager.acknowledgeAndDelete(record.getId());
            return;
        }
        if (taskMapper.claimForExecution(taskId) <= 0) {
            meterRegistry.counter("ai_generation_stream_total", "outcome", "duplicate").increment();
            log.info("[AI_GENERATION] stream record skipped because task was not claimable taskId={} recordId={}",
                    taskId, record.getId().getValue());
            streamManager.acknowledgeAndDelete(record.getId());
            return;
        }
        log.info("[AI_GENERATION] task claimed for execution taskId={} recordId={} status=RUNNING",
                taskId, record.getId().getValue());
        try {
            taskService.execute(taskId);
            streamManager.acknowledgeAndDelete(record.getId());
            meterRegistry.counter("ai_generation_stream_total", "outcome", "handled").increment();
            log.info("[AI_GENERATION] stream record handled taskId={} recordId={}",
                    taskId, record.getId().getValue());
        } catch (Throwable throwable) {
            meterRegistry.counter("ai_generation_stream_total", "outcome", "unexpected").increment();
            log.error("[AI_GENERATION] unexpected consumer failure taskId={} recordId={} errorType={}",
                    taskId, record.getId().getValue(), throwable.getClass().getSimpleName(), throwable);
        }
    }
}

package com.qwerlty.myojbackendaiservice.queue;

import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.manager.GenerationAdmissionControl;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationLane;
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
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;
import org.springframework.stereotype.Component;
import java.util.Date;

@Slf4j
@Component
public class GenerationStreamConsumer {
    private final AiProblemGenerationTaskMapper taskMapper;
    private final GenerationTaskService taskService;
    private final GenerationStreamManager streamManager;
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private final MeterRegistry meterRegistry;
    private final GenerationAdmissionControl admissionControl;
    private final String consumerPrefix;
    private final int publicConcurrency;
    private final int reviewConcurrency;

    public GenerationStreamConsumer(
            AiProblemGenerationTaskMapper taskMapper,
            GenerationTaskService taskService,
            GenerationStreamManager streamManager,
            @Qualifier("generationStreamListenerContainer")
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
            MeterRegistry meterRegistry,
            GenerationAdmissionControl admissionControl,
            @Value("${myoj.ai.generation.stream.consumer-prefix:${HOSTNAME:ai-generation}}") String consumerPrefix,
            @Value("${myoj.ai.generation.public-concurrency:${myoj.ai.generation.concurrency:2}}") int publicConcurrency,
            @Value("${myoj.ai.generation.review-concurrency:1}") int reviewConcurrency) {
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.streamManager = streamManager;
        this.container = container;
        this.meterRegistry = meterRegistry;
        this.admissionControl = admissionControl;
        this.consumerPrefix = consumerPrefix;
        this.publicConcurrency = Math.max(1, publicConcurrency);
        this.reviewConcurrency = Math.max(1, reviewConcurrency);
    }

    @PostConstruct
    public void subscribe() {
        log.info("[AI_GENERATION] starting stream consumers consumerPrefix={} publicConcurrency={} reviewConcurrency={}",
                consumerPrefix, publicConcurrency, reviewConcurrency);
        streamManager.ensureGroup();
        subscribe(GenerationLane.PUBLIC_AUTHORING, publicConcurrency);
        subscribe(GenerationLane.ADMIN_REVIEW, reviewConcurrency);
        container.start();
        log.info("[AI_GENERATION] stream consumers started");
    }

    private void subscribe(GenerationLane lane, int concurrency) {
        for (int index = 0; index < concurrency; index++) {
            StreamReadRequest<String> request = StreamReadRequest
                    .builder(StreamOffset.create(streamManager.getStreamKey(lane), ReadOffset.lastConsumed()))
                    .consumer(Consumer.from(streamManager.getGroup(lane),
                            consumerPrefix + "-" + lane.name().toLowerCase() + "-" + index))
                    .autoAcknowledge(false)
                    .cancelOnError(throwable -> false)
                    .build();
            container.register(request, record -> consume(lane, record));
        }
    }

    public void consume(MapRecord<String, String, String> record) {
        GenerationLane lane;
        try {
            lane = GenerationLane.valueOf(record.getValue().getOrDefault("lane", GenerationLane.PUBLIC_AUTHORING.name()));
        } catch (IllegalArgumentException exception) {
            lane = GenerationLane.PUBLIC_AUTHORING;
        }
        consume(lane, record);
    }

    public void consume(GenerationLane lane, MapRecord<String, String, String> record) {
        log.info("[AI_GENERATION] stream record received recordId={}", record.getId().getValue());
        Long taskId;
        try {
            taskId = Long.valueOf(record.getValue().get("taskId"));
        } catch (RuntimeException exception) {
            meterRegistry.counter("ai_generation_stream_total", "outcome", "invalid").increment();
            log.warn("[AI_GENERATION] invalid stream record discarded recordId={} errorType={}",
                    record.getId().getValue(), exception.getClass().getSimpleName());
            streamManager.acknowledgeAndDelete(lane, record.getId());
            return;
        }
        AiProblemGenerationTask task = taskMapper.selectById(taskId);
        if (task == null) {
            streamManager.acknowledgeAndDelete(lane, record.getId());
            return;
        }
        if (!admissionControl.tryStart(task)) {
            taskMapper.deferPending(taskId, new Date(System.currentTimeMillis() + 5_000L));
            streamManager.acknowledgeAndDelete(lane, record.getId());
            meterRegistry.counter("ai_generation_stream_total", "outcome", "deferred_user_slot").increment();
            return;
        }
        if (taskMapper.claimForExecution(taskId) <= 0) {
            admissionControl.revertStart(task);
            meterRegistry.counter("ai_generation_stream_total", "outcome", "duplicate").increment();
            log.info("[AI_GENERATION] stream record skipped because task was not claimable taskId={} recordId={}",
                    taskId, record.getId().getValue());
            streamManager.acknowledgeAndDelete(lane, record.getId());
            return;
        }
        log.info("[AI_GENERATION] task claimed for execution taskId={} recordId={} status=RUNNING",
                taskId, record.getId().getValue());
        try {
            taskService.execute(taskId);
            streamManager.acknowledgeAndDelete(lane, record.getId());
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

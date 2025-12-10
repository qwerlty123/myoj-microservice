package com.qwerlty.myojbackendaiservice.job;

import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GenerationPayloadRetentionJob {
    private final AiProblemGenerationTaskMapper mapper;
    private final MeterRegistry metrics;
    private final int batchSize;

    public GenerationPayloadRetentionJob(AiProblemGenerationTaskMapper mapper,
                                         MeterRegistry metrics,
                                         @Value("${myoj.ai.generation.retention.batch-size:100}") int batchSize) {
        this.mapper = mapper;
        this.metrics = metrics;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${myoj.ai.generation.retention.cron:0 20 3 * * *}", zone = "Asia/Shanghai")
    public void purgeLargePayloads() {
        for (AiProblemGenerationTask task : mapper.listPayloadsToPurge(batchSize)) {
            if (mapper.purgePayload(task.getId()) > 0) {
                metrics.counter("ai_generation_payload_purged_total").increment();
            }
        }
    }
}

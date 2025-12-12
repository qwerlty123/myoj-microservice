package com.qwerlty.myojbackendaiservice.config;

import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.generation.AiModelGateway;
import com.qwerlty.myojbackendaiservice.generation.sandbox.AuthoringSandboxVerifier;
import com.qwerlty.myojbackendaiservice.manager.DistributedLeaseManager;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationLane;
import com.qwerlty.myojbackendaiservice.queue.GenerationStreamManager;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

@Component
public class GenerationMetrics {
    public GenerationMetrics(MeterRegistry registry,
                             AiProblemGenerationTaskMapper mapper,
                             GenerationStreamManager streams,
                             DistributedLeaseManager leases,
                             AiModelGateway modelGateway,
                             AuthoringSandboxVerifier sandboxVerifier,
                             @Qualifier("problemGenerationPublicExecutor") ExecutorService publicExecutor,
                             @Qualifier("problemGenerationReviewExecutor") ExecutorService reviewExecutor) {
        for (GenerationLane lane : GenerationLane.values()) {
            Gauge.builder("ai_generation_inflight", mapper,
                            value -> safe(() -> value.countInflightByLane(lane.name())))
                    .tag("lane", lane.name()).register(registry);
            Gauge.builder("ai_generation_stream_length", streams,
                            value -> safe(() -> value.streamLength(lane)))
                    .tag("lane", lane.name()).register(registry);
            Gauge.builder("ai_generation_stream_pending", streams,
                            value -> safe(() -> value.pendingCount(lane)))
                    .tag("lane", lane.name()).register(registry);
            Gauge.builder("ai_generation_permits_in_use", leases,
                            value -> safe(() -> value.inUse("model:" + lane.name())))
                    .tag("dependency", "model").tag("lane", lane.name()).register(registry);
            Gauge.builder("ai_generation_permits_in_use", leases,
                            value -> safe(() -> value.inUse("sandbox:" + lane.name())))
                    .tag("dependency", "sandbox").tag("lane", lane.name()).register(registry);
        }
        Gauge.builder("ai_generation_circuit_open", modelGateway,
                        value -> value.isCircuitOpen() ? 1 : 0)
                .tag("dependency", "model").register(registry);
        Gauge.builder("ai_generation_circuit_open", sandboxVerifier,
                        value -> value.isCircuitOpen() ? 1 : 0)
                .tag("dependency", "sandbox").register(registry);
        Gauge.builder("ai_generation_oldest_pending_seconds", mapper,
                        value -> safe(value::oldestPendingSeconds)).register(registry);
        registerExecutor(registry, "public", publicExecutor);
        registerExecutor(registry, "review", reviewExecutor);
    }

    private void registerExecutor(MeterRegistry registry, String lane, ExecutorService executor) {
        if (!(executor instanceof ThreadPoolExecutor pool)) return;
        Gauge.builder("ai_generation_executor_active", pool, ThreadPoolExecutor::getActiveCount)
                .tag("lane", lane).register(registry);
        Gauge.builder("ai_generation_executor_queue", pool, value -> value.getQueue().size())
                .tag("lane", lane).register(registry);
    }

    private static double safe(LongSupplier supplier) {
        try { return supplier.get(); } catch (RuntimeException ignored) { return Double.NaN; }
    }

    @FunctionalInterface
    private interface LongSupplier { long get(); }
}

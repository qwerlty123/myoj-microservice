package com.qwerlty.myojbackendaiservice.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class AiMetrics {

    private final MeterRegistry registry;

    public AiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void stopNode(Timer.Sample sample, String node, String outcome) {
        sample.stop(Timer.builder("ai_graph_node_duration")
                .description("AI authoring graph node duration")
                .tag("node", node)
                .tag("outcome", outcome)
                .register(registry));
    }

    public void recordModelCall(String scene, String outcome, long durationMs) {
        Timer.builder("ai_model_call_duration")
                .tag("scene", scene)
                .tag("outcome", outcome)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void task(String status) {
        Counter.builder("ai_task_total").tag("status", status).register(registry).increment();
    }

    public void tool(String tool, String status) {
        Counter.builder("ai_tool_call_total")
                .tag("tool", tool)
                .tag("status", status)
                .register(registry)
                .increment();
    }

    public void sandbox(String result) {
        Counter.builder("ai_sandbox_validation_total")
                .tag("result", result)
                .register(registry)
                .increment();
    }
}

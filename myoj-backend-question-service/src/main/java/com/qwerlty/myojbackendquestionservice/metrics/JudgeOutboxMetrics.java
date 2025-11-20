package com.qwerlty.myojbackendquestionservice.metrics;

import com.qwerlty.myojbackendquestionservice.mapper.JudgeTaskOutboxMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Exposes the current outbox backlog to Prometheus without querying the database
 * during every Prometheus scrape.
 */
@Component
@Slf4j
public class JudgeOutboxMetrics {

    private static final int STATUS_PENDING = 0;
    private static final int STATUS_DEAD = 2;
    private static final int STATUS_DISPATCHING = 3;

    private final JudgeTaskOutboxMapper judgeTaskOutboxMapper;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();
    private final AtomicLong dispatching = new AtomicLong();

    public JudgeOutboxMetrics(JudgeTaskOutboxMapper judgeTaskOutboxMapper, MeterRegistry meterRegistry) {
        this.judgeTaskOutboxMapper = judgeTaskOutboxMapper;
        registerGauge(meterRegistry, "pending", pending);
        registerGauge(meterRegistry, "dead", dead);
        registerGauge(meterRegistry, "dispatching", dispatching);
    }

    private void registerGauge(MeterRegistry meterRegistry, String status, AtomicLong value) {
        Gauge.builder("myoj.judge.outbox", value, AtomicLong::get)
                .description("Number of judge outbox records by status")
                .tag("status", status)
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${judge-consistency.metrics.refresh-interval-ms:5000}")
    public void refresh() {
        try {
            pending.set(judgeTaskOutboxMapper.countByStatus(STATUS_PENDING));
            dead.set(judgeTaskOutboxMapper.countByStatus(STATUS_DEAD));
            dispatching.set(judgeTaskOutboxMapper.countByStatus(STATUS_DISPATCHING));
        } catch (Exception e) {
            log.warn("Unable to refresh judge outbox metrics", e);
        }
    }
}

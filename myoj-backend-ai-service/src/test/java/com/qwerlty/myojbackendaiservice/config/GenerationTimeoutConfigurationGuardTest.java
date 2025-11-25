package com.qwerlty.myojbackendaiservice.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationTimeoutConfigurationGuardTest {

    @Test
    void acceptsTaskBudgetWithRecoveryGrace() {
        assertThatCode(() -> new GenerationTimeoutConfigurationGuard(
                720_000L, 1_080_000L, 900_000L, 1_380_000L))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsRecoveryThresholdThatCanReclaimAStillRunningTask() {
        assertThatThrownBy(() -> new GenerationTimeoutConfigurationGuard(
                720_000L, 1_080_000L, 900_000L, 1_150_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少晚 120 秒");
    }

    @Test
    void rejectsAnyWorkflowBudgetBelowOneMinute() {
        assertThatThrownBy(() -> new GenerationTimeoutConfigurationGuard(
                59_999L, 1_080_000L, 900_000L, 1_380_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能小于 60 秒");
    }
}

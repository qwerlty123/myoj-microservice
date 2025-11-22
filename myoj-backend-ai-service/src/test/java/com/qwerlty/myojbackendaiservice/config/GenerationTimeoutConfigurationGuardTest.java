package com.qwerlty.myojbackendaiservice.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationTimeoutConfigurationGuardTest {

    @Test
    void acceptsTaskBudgetWithRecoveryGrace() {
        assertThatCode(() -> new GenerationTimeoutConfigurationGuard(900_000L, 1_200_000L))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsRecoveryThresholdThatCanReclaimAStillRunningTask() {
        assertThatThrownBy(() -> new GenerationTimeoutConfigurationGuard(900_000L, 960_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少晚 120 秒");
    }
}

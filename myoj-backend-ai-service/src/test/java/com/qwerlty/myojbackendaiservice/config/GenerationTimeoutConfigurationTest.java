package com.qwerlty.myojbackendaiservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationTimeoutConfigurationTest {

    @Test
    void generationBudgetCoversSlowStructuredModelCallsWithoutPrematureRecovery() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> yamlSources = new YamlPropertySourceLoader()
                .load("applicationConfig", new ClassPathResource("application.yml"));
        yamlSources.forEach(environment.getPropertySources()::addLast);

        long taskTimeoutMs = environment.getRequiredProperty(
                "myoj.ai.generation.task-timeout-ms", Long.class);
        long runningTimeoutMs = environment.getRequiredProperty(
                "myoj.ai.generation.running-timeout-ms", Long.class);

        assertThat(taskTimeoutMs).isGreaterThanOrEqualTo(Duration.ofMinutes(15).toMillis());
        assertThat(runningTimeoutMs - taskTimeoutMs)
                .isGreaterThanOrEqualTo(Duration.ofMinutes(2).toMillis());
    }
}

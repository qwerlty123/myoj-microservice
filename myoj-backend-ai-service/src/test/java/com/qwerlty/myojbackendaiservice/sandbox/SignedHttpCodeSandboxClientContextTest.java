package com.qwerlty.myojbackendaiservice.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class SignedHttpCodeSandboxClientContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "myoj.ai.generation.sandbox.url=http://sandbox.test/executeCode",
                    "myoj.ai.generation.sandbox.secret-key=test-secret");

    @Test
    void springCreatesTheSignedSandboxClientUsingItsProductionConstructor() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SignedHttpCodeSandboxClient.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SignedHttpCodeSandboxClient.class)
    static class TestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}

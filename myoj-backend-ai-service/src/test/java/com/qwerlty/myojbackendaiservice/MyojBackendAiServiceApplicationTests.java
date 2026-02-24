package com.qwerlty.myojbackendaiservice;

import com.mysql.cj.jdbc.AbandonedConnectionCleanupThread;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class MyojBackendAiServiceApplicationTests {

    @Test
    void contextLoads() {
        try {
            try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(
                    MyojBackendAiServiceApplication.class
            )
                    .web(WebApplicationType.NONE)
                    .run(
                            "--spring.profiles.active=test",
                            "--spring.cloud.discovery.enabled=false",
                            "--spring.cloud.nacos.discovery.enabled=false",
                            "--spring.ai.openai.api-key=test-key",
                            "--spring.ai.openai.chat.api-key=test-key",
                            "--myoj.security.gateway-token=test-token",
                            "--myoj.ai.sandbox.secret-key=test-secret",
                            "--myoj.ai.authoring.enabled=false"
                    )) {
                // Starting the full context is the assertion.
            }
        } finally {
            AbandonedConnectionCleanupThread.checkedShutdown();
        }
    }
}

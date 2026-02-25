package com.qwerlty.myojbackendaiservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiProductionConfigurationGuardTest {

    @Test
    void rejectsMissingRequiredProductionSecret() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("myoj.ai.sandbox.secret-key", "");

        assertThatThrownBy(() -> new AiProductionConfigurationGuard(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CODESANDBOX_SECRET_KEY");
    }

    @Test
    void rejectsDefaultGatewayTrustToken() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("myoj.security.gateway-token", "change-me-gateway-token");

        assertThatThrownBy(() -> new AiProductionConfigurationGuard(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("默认网关可信令牌");
    }

    @Test
    void rejectsExamplePlaceholderSecrets() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("spring.ai.openai.chat.api-key", "CHANGE_ME_WITH_YOUR_MODEL_API_KEY");

        assertThatThrownBy(() -> new AiProductionConfigurationGuard(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("占位配置")
                .hasMessageContaining("AI_CHAT_API_KEY");
    }

    @Test
    void rejectsMissingRedisCheckpointPassword() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("myoj.ai.authoring.checkpoint.password", "");

        assertThatThrownBy(() -> new AiProductionConfigurationGuard(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REDIS_PASSWORD");
    }

    @Test
    void acceptsCompleteProductionConfiguration() {
        assertThatCode(() -> new AiProductionConfigurationGuard(validEnvironment()))
                .doesNotThrowAnyException();
    }

    private static MockEnvironment validEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.ai.openai.chat.api-key", "model-key")
                .withProperty("myoj.security.gateway-token", "gateway-trust-token-for-test")
                .withProperty("myoj.ai.sandbox.secret-key", "sandbox-key")
                .withProperty("myoj.ai.sandbox.url", "http://sandbox/executeCode")
                .withProperty("myoj.ai.authoring.checkpoint.password", "redis-key");
    }
}

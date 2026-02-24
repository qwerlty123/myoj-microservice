package com.qwerlty.myojbackendaiservice.config;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Profile("prod")
public class AiProductionConfigurationGuard {

    public AiProductionConfigurationGuard(Environment environment) {
        requireSecret(environment, "spring.ai.openai.chat.api-key", "AI_CHAT_API_KEY");
        requireSecret(environment, "myoj.security.gateway-token", "GATEWAY_TRUST_TOKEN");
        requireSecret(environment, "myoj.ai.sandbox.secret-key", "CODESANDBOX_SECRET_KEY");
        requireSecret(environment, "myoj.ai.sandbox.url", "CODESANDBOX_URL");
        requireSecret(environment, "myoj.ai.authoring.checkpoint.password", "REDIS_PASSWORD");
        String gatewayToken = environment.getProperty("myoj.security.gateway-token");
        if ("change-me-gateway-token".equals(gatewayToken)) {
            throw new IllegalStateException("prod 环境禁止使用默认网关可信令牌");
        }
    }

    private static void require(Environment environment, String property, String environmentName) {
        if (!StringUtils.hasText(environment.getProperty(property))) {
            throw new IllegalStateException("prod 环境缺少必需配置：" + environmentName);
        }
    }

    private static void requireSecret(Environment environment, String property, String environmentName) {
        require(environment, property, environmentName);
        String value = environment.getProperty(property, "").trim();
        if (value.toUpperCase().contains("CHANGE_ME")) {
            throw new IllegalStateException("prod 环境禁止使用占位配置：" + environmentName);
        }
    }
}

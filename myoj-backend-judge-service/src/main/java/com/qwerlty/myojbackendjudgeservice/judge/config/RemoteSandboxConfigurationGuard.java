package com.qwerlty.myojbackendjudgeservice.judge.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.net.URI;

@Component
@Slf4j
@ConditionalOnProperty(name = "codesandbox.type", havingValue = "remote")
public class RemoteSandboxConfigurationGuard {

    private final String sandboxUrl;
    private final String secretKey;
    private final int timeoutMillis;

    public RemoteSandboxConfigurationGuard(
            @Value("${codesandbox.url:}") String sandboxUrl,
            @Value("${codesandbox.secretKey:}") String secretKey,
            @Value("${codesandbox.timeoutMillis:120000}") int timeoutMillis) {
        this.sandboxUrl = sandboxUrl;
        this.secretKey = secretKey;
        this.timeoutMillis = timeoutMillis;
    }

    @PostConstruct
    void validate() {
        if (!isHttpUrl(sandboxUrl)) {
            throw new IllegalStateException(
                    "CODESANDBOX_URL 未配置或格式错误，应为 http(s)://host:port/executeCode");
        }
        if (StringUtils.length(secretKey) < 32) {
            throw new IllegalStateException(
                    "CODESANDBOX_SECRET_KEY 未配置或长度不足，必须与远程沙箱保持一致");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalStateException("codesandbox.timeoutMillis 必须大于 0");
        }
        log.info("remote code sandbox configured, url={}, timeoutMillis={}", sandboxUrl, timeoutMillis);
    }

    private boolean isHttpUrl(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && StringUtils.isNotBlank(uri.getHost());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}

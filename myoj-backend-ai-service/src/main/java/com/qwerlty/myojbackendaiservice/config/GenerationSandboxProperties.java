package com.qwerlty.myojbackendaiservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "myoj.ai.generation.sandbox")
public class GenerationSandboxProperties {
    private String url = "http://localhost:8090/executeCode";
    private String secretKey;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 120000;
    private int outputLimitBytes = 1048576;
}

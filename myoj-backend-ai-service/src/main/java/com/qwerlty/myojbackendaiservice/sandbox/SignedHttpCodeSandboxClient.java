package com.qwerlty.myojbackendaiservice.sandbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.config.GenerationSandboxProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

@Component
@EnableConfigurationProperties(GenerationSandboxProperties.class)
public class SignedHttpCodeSandboxClient implements CodeSandboxClient {

    private final ObjectMapper objectMapper;
    private final GenerationSandboxProperties properties;
    private final RestClient restClient;

    @Autowired
    public SignedHttpCodeSandboxClient(ObjectMapper objectMapper,
                                       GenerationSandboxProperties properties) {
        this(objectMapper, properties, restClient(properties));
    }

    SignedHttpCodeSandboxClient(ObjectMapper objectMapper,
                                GenerationSandboxProperties properties,
                                RestClient restClient) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.restClient = restClient;
    }

    private static RestClient restClient(GenerationSandboxProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public SandboxExecuteResponse execute(String language,
                                          String code,
                                          List<String> inputs,
                                          long timeLimitMs,
                                          long memoryLimitKb,
                                          long stackLimitKb) {
        if (!StringUtils.hasText(properties.getSecretKey())) {
            throw new IllegalStateException("代码沙箱密钥未配置");
        }
        SandboxExecuteRequest request = new SandboxExecuteRequest(
                inputs,
                code,
                language,
                new SandboxExecutionProfile("AI_VALIDATION", timeLimitMs, memoryLimitKb,
                        stackLimitKb, properties.getOutputLimitBytes()));
        String body = json(request);
        long timestamp = System.currentTimeMillis();
        SandboxExecuteResponse response = restClient.post()
                .uri(properties.getUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Timestamp", String.valueOf(timestamp))
                .header("X-Signature", sign(properties.getSecretKey(), timestamp, body))
                .body(body)
                .retrieve()
                .body(SandboxExecuteResponse.class);
        if (response == null || response.getStatus() == null) {
            throw new IllegalStateException("代码沙箱返回为空");
        }
        if (Integer.valueOf(2).equals(response.getStatus())) {
            if (response.getMessage() != null
                    && response.getMessage().contains("沙箱运行时缺少编译命令")) {
                throw new SandboxConfigurationException(response.getMessage());
            }
            throw new ResourceAccessException("代码沙箱报告系统错误");
        }
        return response;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("沙箱请求无法序列化", exception);
        }
    }

    private String sign(String secretKey, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "\n" + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("沙箱请求签名失败", exception);
        }
    }
}

package com.qwerlty.myojbackendaiservice.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

@Component
public class SignedCodeSandboxClient {

    private final AiAgentProperties.Sandbox properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public SignedCodeSandboxClient(AiAgentProperties properties, ObjectMapper objectMapper) {
        this(properties.getSandbox(), objectMapper, createRestClient(properties.getSandbox()));
    }

    SignedCodeSandboxClient(AiAgentProperties.Sandbox properties, ObjectMapper objectMapper, RestClient restClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    public SandboxExecuteResponse execute(String code, String language, List<String> inputList,
                                           SandboxExecutionProfile executionProfile) {
        validate(code, language, inputList);
        try {
            String body = objectMapper.writeValueAsString(
                    new SandboxExecuteRequest(List.copyOf(inputList), code, language.toLowerCase(), executionProfile));
            long timestamp = System.currentTimeMillis();
            SandboxExecuteResponse response = restClient.post()
                    .uri(properties.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Timestamp", Long.toString(timestamp))
                    .header("X-Signature", sign(properties.getSecretKey(), timestamp, body))
                    .body(body)
                    .retrieve()
                    .body(SandboxExecuteResponse.class);
            if (response == null) {
                throw new IllegalStateException("代码沙箱返回为空");
            }
            return response;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("调用代码沙箱失败：" + concise(exception.getMessage()), exception);
        }
    }

    private void validate(String code, String language, List<String> inputList) {
        if (!"java".equalsIgnoreCase(language)) {
            throw new IllegalArgumentException("language 只能是 java");
        }
        if (!StringUtils.hasText(code) || code.length() > properties.getMaxCodeLength()) {
            throw new IllegalArgumentException("代码为空或长度超过限制");
        }
        if (inputList == null || inputList.isEmpty() || inputList.size() > properties.getMaxCases()) {
            throw new IllegalArgumentException("测试输入数量必须在 1 到 " + properties.getMaxCases() + " 之间");
        }
        if (!StringUtils.hasText(properties.getSecretKey())) {
            throw new IllegalStateException("代码沙箱密钥未配置");
        }
    }

    public static String sign(String secretKey, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal((timestamp + "\n" + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("代码沙箱请求签名失败", exception);
        }
    }

    private static RestClient createRestClient(AiAgentProperties.Sandbox properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    private static String concise(String message) {
        if (!StringUtils.hasText(message)) {
            return "未知错误";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}

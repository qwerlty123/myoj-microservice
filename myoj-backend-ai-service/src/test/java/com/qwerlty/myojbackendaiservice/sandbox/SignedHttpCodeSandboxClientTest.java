package com.qwerlty.myojbackendaiservice.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.config.GenerationSandboxProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SignedHttpCodeSandboxClientTest {

    private MockRestServiceServer server;
    private SignedHttpCodeSandboxClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        GenerationSandboxProperties properties = new GenerationSandboxProperties();
        properties.setUrl("http://sandbox.test/executeCode");
        properties.setSecretKey("sandbox-secret");
        client = new SignedHttpCodeSandboxClient(new ObjectMapper(), properties, builder.build());
    }

    @Test
    void signsTheExactJsonBodyAcceptedByTheSandboxContract() {
        server.expect(requestTo("http://sandbox.test/executeCode"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(request -> {
                    String timestamp = request.getHeaders().getFirst("X-Timestamp");
                    String signature = request.getHeaders().getFirst("X-Signature");
                    String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                            .getBodyAsString();
                    assertThat(signature).isEqualTo(sign("sandbox-secret", timestamp, body));
                    assertThat(body).contains("\"language\":\"java\"", "\"purpose\":\"AI_VALIDATION\"");
                })
                .andRespond(withSuccess("""
                        {"status":1,"outputList":["2"],
                         "judgeInfo":{"message":null,"memory":0,"time":12},
                         "caseResults":[{"index":0,"exitCode":0,"output":"2","error":"",
                         "timeMs":12,"timedOut":false,"outputLimitExceeded":false}]}
                        """, MediaType.APPLICATION_JSON));

        SandboxExecuteResponse response = client.execute(
                "java", "public class Main {}", List.of("1"), 1000, 262144, 65536);

        assertThat(response.getOutputList()).containsExactly("2");
        assertThat(response.getJudgeInfo().getTime()).isEqualTo(12L);
        assertThat(response.getCaseResults()).singleElement().satisfies(result -> {
            assertThat(result.getIndex()).isZero();
            assertThat(result.getExitCode()).isZero();
            assertThat(result.getTimeMs()).isEqualTo(12L);
        });
        server.verify();
    }

    @Test
    void convertsSandboxSystemStatusIntoRetryableDependencyFailure() {
        server.expect(requestTo("http://sandbox.test/executeCode"))
                .andRespond(withSuccess("{\"status\":2,\"message\":\"容器运行时不可用\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.execute(
                "go", "package main", List.of("1"), 1000, 262144, 65536))
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void treatsAMissingCompilerAsPermanentSandboxMisconfiguration() {
        server.expect(requestTo("http://sandbox.test/executeCode"))
                .andRespond(withSuccess(
                        "{\"status\":2,\"message\":\"沙箱运行时缺少编译命令: go\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.execute(
                "go", "package main", List.of("1"), 1000, 262144, 65536))
                .isInstanceOf(SandboxConfigurationException.class)
                .hasMessageContaining("go");
    }

    private String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                    (timestamp + "\n" + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

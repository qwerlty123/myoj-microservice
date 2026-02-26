package com.qwerlty.myojbackendgateway.web;

import com.qwerlty.myojbackendcommon.common.BaseResponse;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayFallbackControllerTest {

    private final GatewayFallbackController controller = new GatewayFallbackController();

    @Test
    void returnsGatewayTimeoutForTimeoutFailures() {
        MockServerWebExchange exchange = exchangeWithCause(new TimeoutException("slow"));

        ResponseEntity<BaseResponse<Void>> response = controller.fallback("question", exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(50400);
        assertThat(response.getBody().getMessage()).contains("题目服务响应超时");
    }

    @Test
    void returnsServiceUnavailableForOpenCircuitOrTransportFailure() {
        MockServerWebExchange exchange = exchangeWithCause(new IllegalStateException("down"));

        ResponseEntity<BaseResponse<Void>> response = controller.fallback("ai", exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(50300);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
    }

    @Test
    void cannotBeUsedAsAPublicFakeFallbackEndpoint() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/fallback/question").build());

        ResponseEntity<BaseResponse<Void>> response = controller.fallback("question", exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static MockServerWebExchange exchangeWithCause(Throwable throwable) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build());
        exchange.getAttributes().put(
                ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR, throwable);
        return exchange;
    }
}

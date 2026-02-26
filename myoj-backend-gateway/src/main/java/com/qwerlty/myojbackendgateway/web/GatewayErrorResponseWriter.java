package com.qwerlty.myojbackendgateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendcommon.common.ErrorCode;
import com.qwerlty.myojbackendcommon.common.ResultUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class GatewayErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public GatewayErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, ErrorCode errorCode,
                            String message, Duration retryAfter) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl("no-store");
        response.getHeaders().set("X-Request-Id", exchange.getRequest().getId());
        if (retryAfter != null && !retryAfter.isNegative() && !retryAfter.isZero()) {
            long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        }

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(ResultUtils.error(errorCode, message));
        } catch (JsonProcessingException exception) {
            body = ("{\"code\":" + errorCode.getCode()
                    + ",\"data\":null,\"message\":\"gateway error\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}

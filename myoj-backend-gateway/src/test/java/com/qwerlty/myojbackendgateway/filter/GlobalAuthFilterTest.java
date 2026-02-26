package com.qwerlty.myojbackendgateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendcommon.utils.JwtUtils;
import com.qwerlty.myojbackendgateway.web.GatewayErrorResponseWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalAuthFilterTest {

    private GlobalAuthFilter filter;
    private ReactiveStringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));
        filter = new GlobalAuthFilter(redisTemplate,
                new GatewayErrorResponseWriter(new ObjectMapper()));
    }

    @Test
    void replacesSpoofedIdentityHeadersWithJwtClaims() {
        String token = JwtUtils.generateToken(7L, "admin");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest
                        .post("/api/ai/chat/message/send")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-user-Id", "999")
                        .header("X-user-Role", "admin,user")
                        .header("X-Gateway-Token", "client-spoof")
                        .build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, value -> {
            forwarded.set(value);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getRequest().getHeaders().get("X-user-Id"))
                .containsExactly("7");
        assertThat(forwarded.get().getRequest().getHeaders().get("X-user-Role"))
                .containsExactly("admin");
        assertThat(forwarded.get().getRequest().getHeaders().get("X-Gateway-Token"))
                .isNull();
    }

    @Test
    void returnsUnauthorizedWhenTokenIsBlacklisted() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(true));
        String token = JwtUtils.generateToken(7L, "user");
        MockServerWebExchange exchange = authenticatedExchange(token);

        filter.filter(exchange, value -> Mono.error(new AssertionError("request must not be forwarded")))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"code\":40100");
    }

    @Test
    void returnsServiceUnavailableWhenRedisCannotBeReached() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.error(new IllegalStateException("redis down")));
        String token = JwtUtils.generateToken(7L, "user");
        MockServerWebExchange exchange = authenticatedExchange(token);

        filter.filter(exchange, value -> Mono.error(new AssertionError("request must not be forwarded")))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"code\":50300");
    }

    @Test
    void rejectsMalformedJwtBeforeCallingRedis() {
        MockServerWebExchange exchange = authenticatedExchange("not-a-jwt");

        filter.filter(exchange, value -> Mono.error(new AssertionError("request must not be forwarded")))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(redisTemplate, never()).hasKey(anyString());
    }

    private MockServerWebExchange authenticatedExchange(String token) {
        return MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest
                        .get("/api/question/list/page")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());
    }
}

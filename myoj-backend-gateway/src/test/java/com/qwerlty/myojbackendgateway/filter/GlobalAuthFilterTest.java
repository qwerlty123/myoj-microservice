package com.qwerlty.myojbackendgateway.filter;

import com.qwerlty.myojbackendcommon.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalAuthFilterTest {

    private GlobalAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new GlobalAuthFilter();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        ReflectionTestUtils.setField(filter, "stringRedisTemplate", redisTemplate);
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
}

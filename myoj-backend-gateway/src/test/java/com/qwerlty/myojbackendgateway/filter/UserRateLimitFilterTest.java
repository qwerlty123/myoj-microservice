package com.qwerlty.myojbackendgateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendgateway.config.UserRateLimitProperties;
import com.qwerlty.myojbackendgateway.web.GatewayErrorResponseWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class UserRateLimitFilterTest {

    private UserRateLimitProperties properties;

    private StubRateLimiter rateLimiter;

    private UserRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        properties = new UserRateLimitProperties();
        UserRateLimitProperties.Rule rule = new UserRateLimitProperties.Rule();
        rule.setId("question-submit");
        rule.setPaths(Collections.singletonList("/api/question/question_submit/do"));
        rule.setMethods(Collections.singletonList("POST"));
        rule.setLimit(2);
        rule.setWindow(Duration.ofSeconds(5));
        properties.getRules().add(rule);

        rateLimiter = new StubRateLimiter();
        filter = new UserRateLimitFilter(properties, rateLimiter,
                new GatewayErrorResponseWriter(new ObjectMapper()));
        filter.afterPropertiesSet();
    }

    @Test
    void rejectsAnAuthenticatedUserWhenTheRuleIsExhausted() {
        rateLimiter.result = Mono.just(RedisFixedWindowRateLimiter.Decision.blocked(
                Duration.ofMillis(2100)));
        MockServerWebExchange exchange = exchangeWithUser();
        AtomicBoolean forwarded = new AtomicBoolean(false);

        filter.filter(exchange, value -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("3");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"code\":42900");
    }

    @Test
    void failsOpenWithoutSwallowingTheRequestWhenRedisIsUnavailable() {
        properties.setFailOpen(true);
        rateLimiter.result = Mono.error(new IllegalStateException("redis unavailable"));
        MockServerWebExchange exchange = exchangeWithUser();
        AtomicBoolean forwarded = new AtomicBoolean(false);

        filter.filter(exchange, value -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void runsAfterAuthenticationAndBeforeRouteFilters() {
        assertThat(GlobalAuthFilter.ORDER).isLessThan(filter.getOrder());
        assertThat(filter.getOrder()).isLessThan(0);
    }

    private static MockServerWebExchange exchangeWithUser() {
        return MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/question/question_submit/do")
                .header("X-user-Id", "7")
                .build());
    }

    private static final class StubRateLimiter extends RedisFixedWindowRateLimiter {

        private Mono<Decision> result = Mono.just(Decision.allowed());

        private StubRateLimiter() {
            super(null);
        }

        @Override
        public Mono<Decision> acquire(UserRateLimitProperties.Rule rule, String userId) {
            return result;
        }
    }
}

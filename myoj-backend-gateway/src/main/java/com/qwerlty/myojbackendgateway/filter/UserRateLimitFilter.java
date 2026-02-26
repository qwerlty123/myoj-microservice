package com.qwerlty.myojbackendgateway.filter;

import com.qwerlty.myojbackendcommon.common.ErrorCode;
import com.qwerlty.myojbackendgateway.config.UserRateLimitProperties;
import com.qwerlty.myojbackendgateway.web.GatewayErrorResponseWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Component
@EnableConfigurationProperties(UserRateLimitProperties.class)
public class UserRateLimitFilter implements GlobalFilter, Ordered, InitializingBean {

    static final int ORDER = -90;

    private static final Logger log = LoggerFactory.getLogger(UserRateLimitFilter.class);

    private final UserRateLimitProperties properties;

    private final RedisFixedWindowRateLimiter rateLimiter;

    private final GatewayErrorResponseWriter responseWriter;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public UserRateLimitFilter(UserRateLimitProperties properties,
                               RedisFixedWindowRateLimiter rateLimiter,
                               GatewayErrorResponseWriter responseWriter) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.responseWriter = responseWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        String userId = exchange.getRequest().getHeaders().getFirst("X-user-Id");
        if (!StringUtils.hasText(userId)) {
            return chain.filter(exchange);
        }

        UserRateLimitProperties.Rule rule = findRule(exchange);
        if (rule == null) {
            return chain.filter(exchange);
        }

        Mono<RedisFixedWindowRateLimiter.Decision> decision = rateLimiter.acquire(rule, userId)
                .onErrorResume(error -> {
                    log.warn("User rate limiter failed for rule {}: {}",
                            rule.getId(), error.toString());
                    return Mono.just(properties.isFailOpen()
                            ? RedisFixedWindowRateLimiter.Decision.allowed()
                            : RedisFixedWindowRateLimiter.Decision.unavailable());
                });

        return decision.flatMap(result -> {
            if (result.isAllowed()) {
                return chain.filter(exchange);
            }
            if (!result.isBackendAvailable()) {
                return responseWriter.write(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorCode.SERVICE_UNAVAILABLE, "限流服务暂时不可用",
                        result.getRetryAfter());
            }
            return responseWriter.write(exchange, HttpStatus.TOO_MANY_REQUESTS,
                    ErrorCode.TOO_MANY_REQUEST, "请求过于频繁，请稍后重试",
                    result.getRetryAfter());
        });
    }

    private UserRateLimitProperties.Rule findRule(ServerWebExchange exchange) {
        String path = exchange.getRequest().getURI().getPath();
        HttpMethod requestMethod = exchange.getRequest().getMethod();
        for (UserRateLimitProperties.Rule rule : properties.getRules()) {
            if (!matchesMethod(rule, requestMethod)) {
                continue;
            }
            for (String pattern : rule.getPaths()) {
                if (pathMatcher.match(pattern, path)) {
                    return rule;
                }
            }
        }
        return null;
    }

    private static boolean matchesMethod(UserRateLimitProperties.Rule rule, HttpMethod requestMethod) {
        if (rule.getMethods() == null || rule.getMethods().isEmpty()) {
            return true;
        }
        if (requestMethod == null) {
            return false;
        }
        for (String method : rule.getMethods()) {
            if (requestMethod.name().equalsIgnoreCase(method)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void afterPropertiesSet() {
        Set<String> ids = new HashSet<>();
        for (UserRateLimitProperties.Rule rule : properties.getRules()) {
            if (!StringUtils.hasText(rule.getId()) || !rule.getId().matches("[A-Za-z0-9_-]+")
                    || rule.getPaths() == null || rule.getPaths().isEmpty()
                    || rule.getLimit() <= 0 || rule.getWindow() == null
                    || rule.getWindow().isNegative() || rule.getWindow().isZero()) {
                throw new IllegalStateException("Invalid user rate-limit rule: " + rule.getId());
            }
            if (!ids.add(rule.getId())) {
                throw new IllegalStateException("Duplicate user rate-limit rule: " + rule.getId());
            }
            for (String path : rule.getPaths()) {
                if (!StringUtils.hasText(path)) {
                    throw new IllegalStateException("Blank path in user rate-limit rule: " + rule.getId());
                }
            }
            if (rule.getMethods() != null) {
                for (String method : rule.getMethods()) {
                    if (!StringUtils.hasText(method)
                            || HttpMethod.resolve(method.toUpperCase(Locale.ROOT)) == null) {
                        throw new IllegalStateException("Invalid HTTP method in user rate-limit rule: "
                                + rule.getId());
                    }
                }
            }
        }
    }
}

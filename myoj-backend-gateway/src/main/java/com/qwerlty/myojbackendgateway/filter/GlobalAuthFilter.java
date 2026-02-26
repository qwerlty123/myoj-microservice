package com.qwerlty.myojbackendgateway.filter;

import com.qwerlty.myojbackendcommon.common.ErrorCode;
import com.qwerlty.myojbackendcommon.utils.JwtUtils;
import com.qwerlty.myojbackendgateway.web.GatewayErrorResponseWriter;
import io.jsonwebtoken.Claims;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;

@Component
public class GlobalAuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GlobalAuthFilter.class);

    static final int ORDER = -100;

    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    private final ReactiveStringRedisTemplate redisTemplate;

    private final GatewayErrorResponseWriter responseWriter;

    // 不需要验证token的路径
    private static final String[] WHITE_LIST = {
            "/api/user/login",
            "/api/user/get/login",
            "/api/user/register",
            "/api/user/logout",
            "/api/doc.html",
            "/api/v3/api-docs",
            "/api/v2/api-docs",
            "/api/swagger-resources",
            "/api/swagger-ui.html",
            "/api/webjars/**",
            "/api/comment/v2/**",
            "/api/question/v2/**",
            "/api/judge/v2/**",
            "/api/user/v2/**",
    };
    @Value("${security.gateway-token:}")
    private String gatewayToken;

    public GlobalAuthFilter(ReactiveStringRedisTemplate redisTemplate,
                            GatewayErrorResponseWriter responseWriter) {
        this.redisTemplate = redisTemplate;
        this.responseWriter = responseWriter;
    }

    /**
     * 判断是否为白名单路径
     * @param path 请求路径
     * @return 是否在白名单中
     */
    private boolean isWhiteListPath(String path) {
        for (String whitePath : WHITE_LIST) {
            if (antPathMatcher.match(whitePath, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        // No caller-provided identity or service credential may cross the gateway, including
        // requests to public allow-listed endpoints.
        request = request.mutate().headers(headers -> {
            headers.remove("X-user-Id");
            headers.remove("X-user-Role");
            headers.remove("X-Gateway-Token");
            headers.remove("X-Internal-Service-Token");
        }).build();
        exchange = exchange.mutate().request(request).build();
        //判断路径中是否包含 inner，只运行内部调用
        if (antPathMatcher.match("/**/inner/**", path)
                || antPathMatcher.match("/**/internal/**", path)) {
            return responseWriter.write(exchange, HttpStatus.FORBIDDEN,
                    ErrorCode.NO_AUTH_ERROR, "无权限", null);
        }
        //公开接口（如登录注册）放行
        if (isWhiteListPath(path)) {
            return chain.filter(exchange);
        }
        //验证 jwt
        String token = request.getHeaders().getFirst("Authorization");
        if (StringUtils.isBlank(token) || !token.startsWith("Bearer ")) {
            return writeUnauthorized(exchange, "未提供token");
        }
        String jwt = token.substring(7);
        ServerWebExchange currentExchange = exchange;
        ServerHttpRequest currentRequest = request;
        return authenticate(currentExchange, currentRequest, jwt)
                .materialize()
                .flatMap(signal -> handleAuthenticationSignal(signal, currentExchange, chain));
    }

    /**
     * Complete authentication before the per-user limiter reads trusted identity headers.
     */
    @Override
    public int getOrder() {
        return ORDER;
    }

    private Mono<Void> writeUnauthorized(ServerWebExchange exchange, String message) {
        return responseWriter.write(exchange, HttpStatus.UNAUTHORIZED,
                ErrorCode.NOT_LOGIN_ERROR, message, null);
    }

    private Mono<ServerWebExchange> authenticate(ServerWebExchange exchange,
                                                  ServerHttpRequest request,
                                                  String token) {
        final Long userId;
        final String userRole;
        try {
            Claims claims = JwtUtils.parseToken(token);
            userId = Long.parseLong(claims.get("userId", String.class));
            userRole = claims.get("userRole", String.class);
            if (userId <= 0 || StringUtils.isBlank(userRole)) {
                throw new IllegalArgumentException("JWT identity claims are incomplete");
            }
        } catch (Exception exception) {
            return Mono.error(new InvalidTokenException("Token无效或已过期"));
        }

        return redisTemplate.hasKey("jwt:blacklist:" + token)
                .defaultIfEmpty(false)
                .flatMap(blocklisted -> {
                    if (Boolean.TRUE.equals(blocklisted)) {
                        return Mono.error(new InvalidTokenException("Token 已失效"));
                    }
                    ServerHttpRequest authenticatedRequest = request.mutate()
                            .headers(headers -> {
                                headers.set("X-user-Id", userId.toString());
                                headers.set("X-user-Role", userRole);
                                if (StringUtils.isNotBlank(gatewayToken)) {
                                    headers.set("X-Gateway-Token", gatewayToken);
                                }
                            })
                            .build();
                    return Mono.just(exchange.mutate().request(authenticatedRequest).build());
                });
    }

    private Mono<Void> handleAuthenticationSignal(Signal<ServerWebExchange> signal,
                                                   ServerWebExchange exchange,
                                                   GatewayFilterChain chain) {
        if (signal.hasValue()) {
            return chain.filter(signal.get());
        }
        Throwable error = signal.getThrowable();
        if (error instanceof InvalidTokenException) {
            return writeUnauthorized(exchange, error.getMessage());
        }
        log.warn("Authentication dependency failed for path {}: {}",
                exchange.getRequest().getURI().getPath(),
                error == null ? "unknown error" : error.toString());
        return responseWriter.write(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.SERVICE_UNAVAILABLE, "认证服务暂时不可用", null);
    }

    private static final class InvalidTokenException extends RuntimeException {

        private InvalidTokenException(String message) {
            super(message);
        }
    }
}

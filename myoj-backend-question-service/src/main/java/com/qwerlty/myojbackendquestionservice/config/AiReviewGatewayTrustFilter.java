package com.qwerlty.myojbackendquestionservice.config;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AiReviewGatewayTrustFilter extends OncePerRequestFilter {
    @Value("${myoj.security.gateway-token:}")
    private String gatewayToken;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/ai-submissions");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String actual = request.getHeader("X-Gateway-Token");
        if (StringUtils.isBlank(gatewayToken) || StringUtils.isBlank(actual)
                || !MessageDigest.isEqual(gatewayToken.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "请求未经过可信网关");
            return;
        }
        chain.doFilter(request, response);
    }
}

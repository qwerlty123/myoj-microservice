package com.qwerlty.myojbackendaiservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class GatewayTrustFilter extends OncePerRequestFilter {

    private final byte[] expectedToken;

    public GatewayTrustFilter(@Value("${myoj.security.gateway-token:}") String gatewayToken) {
        this.expectedToken = gatewayToken == null ? new byte[0] : gatewayToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.endsWith("/actuator/health") || path.contains("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String actual = request.getHeader("X-Gateway-Token");
        byte[] actualToken = actual == null ? new byte[0] : actual.getBytes(StandardCharsets.UTF_8);
        if (expectedToken.length == 0 || !MessageDigest.isEqual(expectedToken, actualToken)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "请求必须经过可信网关");
            return;
        }
        filterChain.doFilter(request, response);
    }
}

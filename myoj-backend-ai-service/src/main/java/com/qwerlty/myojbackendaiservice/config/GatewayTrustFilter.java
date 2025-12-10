package com.qwerlty.myojbackendaiservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.common.BaseResponse;
import com.qwerlty.myojbackendaiservice.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class GatewayTrustFilter extends OncePerRequestFilter {
    private final String gatewayToken;
    private final ObjectMapper objectMapper;

    public GatewayTrustFilter(@Value("${myoj.security.gateway-token}") String gatewayToken,
                              ObjectMapper objectMapper) {
        this.gatewayToken = gatewayToken;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.startsWith("/generation/") && !path.equals("/generation/tasks");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String actual = request.getHeader("X-Gateway-Token");
        boolean valid = gatewayToken != null && !gatewayToken.isBlank() && actual != null
                && MessageDigest.isEqual(gatewayToken.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), new BaseResponse<Void>(
                    ErrorCode.NO_AUTH_ERROR.getCode(), null, "请求未经过可信网关"));
            return;
        }
        chain.doFilter(request, response);
    }
}

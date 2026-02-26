package com.qwerlty.myojbackendgateway.web;

import com.qwerlty.myojbackendcommon.common.BaseResponse;
import com.qwerlty.myojbackendcommon.common.ErrorCode;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/fallback")
public class GatewayFallbackController {

    private static final Map<String, String> SERVICE_NAMES = createServiceNames();

    @RequestMapping("/{service}")
    public ResponseEntity<BaseResponse<Void>> fallback(@PathVariable String service,
                                                        ServerWebExchange exchange) {
        Throwable cause = exchange.getAttribute(
                ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        if (cause == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new BaseResponse<>(ErrorCode.NOT_FOUND_ERROR));
        }

        boolean timeout = isTimeout(cause);
        HttpStatus status = timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.SERVICE_UNAVAILABLE;
        ErrorCode errorCode = timeout ? ErrorCode.GATEWAY_TIMEOUT : ErrorCode.SERVICE_UNAVAILABLE;
        String serviceName = SERVICE_NAMES.getOrDefault(service, "下游服务");
        String message = timeout
                ? serviceName + "响应超时，请稍后重试"
                : serviceName + "暂时不可用，请稍后重试";

        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.set(HttpHeaders.RETRY_AFTER, timeout ? "1" : "5");
        headers.set("X-Request-Id", exchange.getRequest().getId());
        return new ResponseEntity<>(new BaseResponse<>(errorCode.getCode(), null, message),
                headers, status);
    }

    private static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException
                    || current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            if (current instanceof ResponseStatusException
                    && ((ResponseStatusException) current).getStatus() == HttpStatus.GATEWAY_TIMEOUT) {
                return true;
            }
            if (current instanceof HttpStatusCodeException
                    && ((HttpStatusCodeException) current).getStatusCode() == HttpStatus.GATEWAY_TIMEOUT) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Map<String, String> createServiceNames() {
        Map<String, String> names = new HashMap<>();
        names.put("user", "用户服务");
        names.put("question", "题目服务");
        names.put("judge", "判题服务");
        names.put("comment", "评论服务");
        names.put("ai", "AI 服务");
        return names;
    }
}

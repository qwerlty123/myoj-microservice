package com.qwerlty.myojbackendaiservice.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException exception) {
        HttpStatus status = statusForCode(exception.getCode());
        if (status.is5xxServerError()) {
            log.error("AI service business error", exception);
        } else {
            log.warn("AI service request rejected: code={}, message={}",
                    exception.getCode(), exception.getMessage());
        }
        return ResponseEntity.status(status)
                .body(ApiResponse.error(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception exception) {
        String message = exception instanceof MethodArgumentNotValidException invalid
                && invalid.getBindingResult().getFieldError() != null
                ? invalid.getBindingResult().getFieldError().getDefaultMessage()
                : "请求参数错误";
        return ResponseEntity.badRequest().body(ApiResponse.error(40000, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("Unhandled AI service error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(50000, "AI 服务暂时不可用"));
    }

    static HttpStatus statusForCode(int code) {
        return switch (code) {
            case 40000 -> HttpStatus.BAD_REQUEST;
            case 40100 -> HttpStatus.UNAUTHORIZED;
            case 40101, 40300 -> HttpStatus.FORBIDDEN;
            case 40400 -> HttpStatus.NOT_FOUND;
            case 42900 -> HttpStatus.TOO_MANY_REQUESTS;
            case 50010 -> HttpStatus.BAD_GATEWAY;
            case 50300 -> HttpStatus.SERVICE_UNAVAILABLE;
            case 50400 -> HttpStatus.GATEWAY_TIMEOUT;
            default -> code >= 50000
                    ? HttpStatus.INTERNAL_SERVER_ERROR
                    : HttpStatus.BAD_REQUEST;
        };
    }
}

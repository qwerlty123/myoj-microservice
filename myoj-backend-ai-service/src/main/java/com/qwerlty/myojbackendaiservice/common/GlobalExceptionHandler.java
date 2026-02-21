package com.qwerlty.myojbackendaiservice.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ApiResponse<Void> handleApiException(ApiException exception) {
        return ApiResponse.error(exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class})
    public ApiResponse<Void> handleValidation(Exception exception) {
        String message = exception instanceof MethodArgumentNotValidException invalid
                && invalid.getBindingResult().getFieldError() != null
                ? invalid.getBindingResult().getFieldError().getDefaultMessage()
                : "请求参数错误";
        return ApiResponse.error(40000, message);
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception exception) {
        log.error("Unhandled AI service error", exception);
        return ApiResponse.error(50000, "AI 服务暂时不可用");
    }
}

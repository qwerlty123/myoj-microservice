package com.qwerlty.myojbackendaiservice.exception;

import com.qwerlty.myojbackendaiservice.common.BaseResponse;
import com.qwerlty.myojbackendaiservice.common.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<BaseResponse<Void>> handleBusinessException(BusinessException exception) {
        BaseResponse<Void> body = new BaseResponse<>(exception.getCode(), null, exception.getMessage());
        if (exception.getCode() >= 42900 && exception.getCode() < 43000) {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "10");
            return new ResponseEntity<>(body, headers, HttpStatus.TOO_MANY_REQUESTS);
        }
        if (exception.getCode() == ErrorCode.AI_UPSTREAM_UNAVAILABLE.getCode()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        return ResponseEntity.ok(body);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class})
    public BaseResponse<Void> handleValidationException(Exception exception) {
        return new BaseResponse<>(ErrorCode.PARAMS_ERROR.getCode(), null, ErrorCode.PARAMS_ERROR.getMessage());
    }

    @ExceptionHandler(Throwable.class)
    public BaseResponse<Void> handleUnexpectedException(Throwable throwable) {
        log.error("Unhandled AI service error", throwable);
        return new BaseResponse<>(ErrorCode.SYSTEM_ERROR.getCode(), null, ErrorCode.SYSTEM_ERROR.getMessage());
    }
}

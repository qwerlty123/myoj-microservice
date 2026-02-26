package com.qwerlty.myojbackendcommon.exception;

import com.qwerlty.myojbackendcommon.common.BaseResponse;
import com.qwerlty.myojbackendcommon.common.ErrorCode;
import com.qwerlty.myojbackendcommon.common.ResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 全局异常处理器
 *
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<BaseResponse<?>> businessExceptionHandler(BusinessException e) {
        HttpStatus status = resolveHttpStatus(e.getCode());
        if (status.is5xxServerError()) {
            log.error("BusinessException", e);
        } else {
            log.warn("BusinessException: code={}, message={}", e.getCode(), e.getMessage());
        }
        return ResponseEntity.status(status).body(ResultUtils.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<BaseResponse<?>> validationExceptionHandler(Exception e) {
        log.warn("Invalid request: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ResultUtils.error(ErrorCode.PARAMS_ERROR));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<BaseResponse<?>> responseStatusExceptionHandler(ResponseStatusException e) {
        HttpStatus status = e.getStatus();
        ErrorCode errorCode = errorCodeForStatus(status);
        String message = e.getReason() == null ? errorCode.getMessage() : e.getReason();
        return ResponseEntity.status(status).body(ResultUtils.error(errorCode, message));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<BaseResponse<?>> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误"));
    }

    static HttpStatus resolveHttpStatus(int code) {
        switch (code) {
            case 40000:
                return HttpStatus.BAD_REQUEST;
            case 40100:
                return HttpStatus.UNAUTHORIZED;
            case 40101:
            case 40300:
                return HttpStatus.FORBIDDEN;
            case 40400:
                return HttpStatus.NOT_FOUND;
            case 42900:
                return HttpStatus.TOO_MANY_REQUESTS;
            case 50010:
                return HttpStatus.BAD_GATEWAY;
            case 50300:
                return HttpStatus.SERVICE_UNAVAILABLE;
            case 50400:
                return HttpStatus.GATEWAY_TIMEOUT;
            default:
                return code >= 50000
                        ? HttpStatus.INTERNAL_SERVER_ERROR
                        : HttpStatus.BAD_REQUEST;
        }
    }

    private static ErrorCode errorCodeForStatus(HttpStatus status) {
        if (status == HttpStatus.BAD_REQUEST) {
            return ErrorCode.PARAMS_ERROR;
        }
        if (status == HttpStatus.UNAUTHORIZED) {
            return ErrorCode.NOT_LOGIN_ERROR;
        }
        if (status == HttpStatus.FORBIDDEN) {
            return ErrorCode.FORBIDDEN_ERROR;
        }
        if (status == HttpStatus.NOT_FOUND) {
            return ErrorCode.NOT_FOUND_ERROR;
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            return ErrorCode.TOO_MANY_REQUEST;
        }
        if (status == HttpStatus.BAD_GATEWAY) {
            return ErrorCode.API_REQUEST_ERROR;
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            return ErrorCode.SERVICE_UNAVAILABLE;
        }
        if (status == HttpStatus.GATEWAY_TIMEOUT) {
            return ErrorCode.GATEWAY_TIMEOUT;
        }
        return status.is4xxClientError() ? ErrorCode.PARAMS_ERROR : ErrorCode.SYSTEM_ERROR;
    }
}

package com.qwerlty.myojbackendcommon.exception;

import com.qwerlty.myojbackendcommon.common.BaseResponse;
import com.qwerlty.myojbackendcommon.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsClientBusinessErrorsToRealHttpStatus() {
        ResponseEntity<BaseResponse<?>> response = handler.businessExceptionHandler(
                new BusinessException(ErrorCode.TOO_MANY_REQUEST));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(42900);
    }

    @Test
    void mapsDependencyErrorsToBadGateway() {
        ResponseEntity<BaseResponse<?>> response = handler.businessExceptionHandler(
                new BusinessException(ErrorCode.API_REQUEST_ERROR));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(50010);
    }

    @Test
    void mapsUnexpectedErrorsToHttp500() {
        ResponseEntity<BaseResponse<?>> response = handler.runtimeExceptionHandler(
                new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(50000);
    }
}

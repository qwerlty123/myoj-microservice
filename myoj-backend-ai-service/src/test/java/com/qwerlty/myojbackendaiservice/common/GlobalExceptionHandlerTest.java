package com.qwerlty.myojbackendaiservice.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void returnsRealHttpStatusForClientRejections() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleApiException(
                ApiException.forbidden("blocked"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(40300);
    }

    @Test
    void returns429ForExecutorOverload() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleApiException(
                ApiException.tooManyRequests("busy"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(42900);
    }

    @Test
    void returns503WhenAiIsDisabled() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleApiException(
                ApiException.serviceUnavailable("disabled"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(50300);
    }
}

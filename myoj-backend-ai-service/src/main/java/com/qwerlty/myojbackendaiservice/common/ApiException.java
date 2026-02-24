package com.qwerlty.myojbackendaiservice.common;

public class ApiException extends RuntimeException {

    private final int code;

    public ApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(40000, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(40100, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(40300, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(40400, message);
    }

    public static ApiException operation(String message) {
        return new ApiException(50001, message);
    }
}

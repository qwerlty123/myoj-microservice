package com.qwerlty.myojbackendaiservice.common;

public record ApiResponse<T>(int code, T data, String message) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, data, "ok");
    }

    public static ApiResponse<Void> error(int code, String message) {
        return new ApiResponse<>(code, null, message);
    }
}

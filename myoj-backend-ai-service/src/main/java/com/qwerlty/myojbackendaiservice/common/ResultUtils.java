package com.qwerlty.myojbackendaiservice.common;

public final class ResultUtils {

    private ResultUtils() {
    }

    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(ErrorCode.SUCCESS.getCode(), data, ErrorCode.SUCCESS.getMessage());
    }

    public static BaseResponse<Void> error(ErrorCode errorCode, String message) {
        return new BaseResponse<>(errorCode.getCode(), null, message);
    }
}

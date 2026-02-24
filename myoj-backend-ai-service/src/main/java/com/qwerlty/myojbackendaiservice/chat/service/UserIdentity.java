package com.qwerlty.myojbackendaiservice.chat.service;

import com.qwerlty.myojbackendaiservice.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;

public final class UserIdentity {

    public static long requireUserId(HttpServletRequest request) {
        String value = request.getHeader("X-User-Id");
        try {
            long userId = Long.parseLong(value);
            if (userId > 0) {
                return userId;
            }
        } catch (Exception ignored) {
            // Report all absent and malformed gateway identities consistently.
        }
        throw ApiException.unauthorized("未登录或网关用户身份无效");
    }

    public static long requireAdminUserId(HttpServletRequest request) {
        long userId = requireUserId(request);
        if (!"admin".equalsIgnoreCase(request.getHeader("X-User-Role"))) {
            throw ApiException.forbidden("仅管理员可以使用 AI 出题任务");
        }
        return userId;
    }

    private UserIdentity() {
    }
}

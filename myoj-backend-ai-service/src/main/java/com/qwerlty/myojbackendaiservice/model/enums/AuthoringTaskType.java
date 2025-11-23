package com.qwerlty.myojbackendaiservice.model.enums;

public enum AuthoringTaskType {
    PROBLEM_DRAFT,
    TEST_CASES,
    QUALITY_REVIEW;

    public static AuthoringTaskType parse(String value) {
        try {
            return AuthoringTaskType.valueOf(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("不支持的 AI 题目创作任务类型: " + value, exception);
        }
    }
}

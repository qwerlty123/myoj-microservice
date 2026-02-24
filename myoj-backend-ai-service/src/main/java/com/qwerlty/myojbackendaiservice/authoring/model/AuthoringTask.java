package com.qwerlty.myojbackendaiservice.authoring.model;

import java.time.LocalDateTime;

public record AuthoringTask(
        long id,
        long userId,
        Long sourceTaskId,
        String idempotencyKey,
        String taskType,
        String requestJson,
        String resultJson,
        AuthoringTaskStatus status,
        AuthoringStage stage,
        int progress,
        int repairCount,
        boolean cancelRequested,
        String errorCode,
        String lastError,
        String modelName,
        String promptVersion,
        String graphVersion,
        LocalDateTime startedTime,
        LocalDateTime finishedTime,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}

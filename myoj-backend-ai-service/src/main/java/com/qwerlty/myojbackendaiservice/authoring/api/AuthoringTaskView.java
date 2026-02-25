package com.qwerlty.myojbackendaiservice.authoring.api;

import java.time.LocalDateTime;

public record AuthoringTaskView(
        String taskId,
        String sourceTaskId,
        String taskType,
        String status,
        String stage,
        int progress,
        int repairCount,
        boolean cancelRequested,
        AuthoringTaskResult result,
        String errorCode,
        String lastError,
        String modelName,
        String promptVersion,
        String graphVersion,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}

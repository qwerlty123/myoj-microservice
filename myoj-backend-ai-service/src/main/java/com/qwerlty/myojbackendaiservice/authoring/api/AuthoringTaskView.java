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
        String traceId,
        String reviewDecision,
        String reviewerId,
        String reviewComment,
        LocalDateTime reviewedTime,
        String publishedQuestionId,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
    public AuthoringTaskView(String taskId,
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
                             LocalDateTime updateTime) {
        this(taskId, sourceTaskId, taskType, status, stage, progress, repairCount,
                cancelRequested, result, errorCode, lastError, modelName, promptVersion,
                graphVersion, null, null, null, null, null, null, createTime, updateTime);
    }
}

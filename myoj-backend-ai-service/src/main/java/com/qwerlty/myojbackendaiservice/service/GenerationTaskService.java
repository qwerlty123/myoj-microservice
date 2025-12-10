package com.qwerlty.myojbackendaiservice.service;

import com.qwerlty.myojbackendaiservice.generation.workflow.AuthoringRequest;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationTaskPageVO;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationTaskVO;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationQuotaVO;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ArtifactValidationRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ArtifactValidationResult;

public interface GenerationTaskService {
    GenerationTaskVO create(AuthoringTaskType type, AuthoringRequest request,
                            Long userId, String role, String idempotencyKey);

    default GenerationTaskVO create(AuthoringTaskType type, AuthoringRequest request,
                                    Long userId, String idempotencyKey) {
        return create(type, request, userId, "admin", idempotencyKey);
    }

    GenerationTaskVO get(Long taskId, Long userId, String role);

    default GenerationTaskVO get(Long taskId, Long userId) {
        return get(taskId, userId, "user");
    }

    GenerationTaskPageVO history(Long userId, String role, int current, int pageSize, AuthoringTaskType type);

    default GenerationTaskPageVO history(Long userId, int current, int pageSize, AuthoringTaskType type) {
        return history(userId, "user", current, pageSize, type);
    }

    GenerationTaskVO retry(Long taskId, Long userId);

    GenerationTaskVO cancel(Long taskId, Long userId);

    void execute(Long taskId);

    GenerationQuotaVO quota(Long userId, String role);

    ArtifactValidationResult validateArtifact(ArtifactValidationRequest request);
}

package com.qwerlty.myojbackendaiservice.service;

import com.qwerlty.myojbackendaiservice.generation.workflow.AuthoringRequest;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationTaskPageVO;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationTaskVO;

public interface GenerationTaskService {
    GenerationTaskVO create(AuthoringTaskType type, AuthoringRequest request,
                            Long userId, String idempotencyKey);

    GenerationTaskVO get(Long taskId, Long userId);

    GenerationTaskPageVO history(Long userId, int current, int pageSize, AuthoringTaskType type);

    GenerationTaskVO retry(Long taskId, Long userId);

    GenerationTaskVO cancel(Long taskId, Long userId);

    void execute(Long taskId);
}

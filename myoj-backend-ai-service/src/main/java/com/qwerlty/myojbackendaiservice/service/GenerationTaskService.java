package com.qwerlty.myojbackendaiservice.service;

import com.qwerlty.myojbackendaiservice.model.dto.generation.GenerationTaskCreateRequest;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationTaskPageVO;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationTaskVO;

public interface GenerationTaskService {
    GenerationTaskVO create(GenerationTaskCreateRequest request, Long userId, String idempotencyKey);

    GenerationTaskVO get(Long taskId, Long userId);

    GenerationTaskPageVO history(Long userId, int current, int pageSize);

    GenerationTaskVO retry(Long taskId, Long userId);

    GenerationTaskVO cancel(Long taskId, Long userId);

    void execute(Long taskId);
}

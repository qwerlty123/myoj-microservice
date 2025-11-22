package com.qwerlty.myojbackendaiservice.controller;

import com.qwerlty.myojbackendaiservice.common.BaseResponse;
import com.qwerlty.myojbackendaiservice.common.ErrorCode;
import com.qwerlty.myojbackendaiservice.common.ResultUtils;
import com.qwerlty.myojbackendaiservice.exception.BusinessException;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GenerationTaskCreateRequest;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationTaskPageVO;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationTaskVO;
import com.qwerlty.myojbackendaiservice.service.GenerationTaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/generation/tasks")
@Validated
@Slf4j
public class GenerationTaskController {
    private final GenerationTaskService taskService;

    public GenerationTaskController(GenerationTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public BaseResponse<GenerationTaskVO> create(
            @Valid @RequestBody GenerationTaskCreateRequest request,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-user-Id") String userId,
            @RequestHeader("X-user-Role") String role) {
        requireAdmin(role);
        Long parsedUserId = parseUserId(userId);
        log.info("[AI_GENERATION] create request received userId={} mode={}",
                parsedUserId, request.getMode());
        GenerationTaskVO task = taskService.create(request, parsedUserId, idempotencyKey);
        log.info("[AI_GENERATION] create request completed taskId={} status={} stage={}",
                task.getTaskId(), task.getStatus(), task.getStage());
        return ResultUtils.success(task);
    }

    @GetMapping("/{taskId}")
    public BaseResponse<GenerationTaskVO> get(
            @PathVariable @Positive Long taskId,
            @RequestHeader("X-user-Id") String userId,
            @RequestHeader("X-user-Role") String role) {
        requireAdmin(role);
        GenerationTaskVO task = taskService.get(taskId, parseUserId(userId));
        log.debug("[AI_GENERATION] task polled taskId={} status={} stage={} progress={}",
                taskId, task.getStatus(), task.getStage(), task.getProgress());
        return ResultUtils.success(task);
    }

    @GetMapping
    public BaseResponse<GenerationTaskPageVO> history(
            @RequestParam(defaultValue = "1") @Positive int current,
            @RequestParam(defaultValue = "10") @Positive int pageSize,
            @RequestHeader("X-user-Id") String userId,
            @RequestHeader("X-user-Role") String role) {
        requireAdmin(role);
        return ResultUtils.success(taskService.history(parseUserId(userId), current, pageSize));
    }

    @PostMapping("/{taskId}/retry")
    public BaseResponse<GenerationTaskVO> retry(
            @PathVariable @Positive Long taskId,
            @RequestHeader("X-user-Id") String userId,
            @RequestHeader("X-user-Role") String role) {
        requireAdmin(role);
        log.info("[AI_GENERATION] retry request received taskId={}", taskId);
        return ResultUtils.success(taskService.retry(taskId, parseUserId(userId)));
    }

    @PostMapping("/{taskId}/cancel")
    public BaseResponse<GenerationTaskVO> cancel(
            @PathVariable @Positive Long taskId,
            @RequestHeader("X-user-Id") String userId,
            @RequestHeader("X-user-Role") String role) {
        requireAdmin(role);
        log.info("[AI_GENERATION] cancel request received taskId={}", taskId);
        return ResultUtils.success(taskService.cancel(taskId, parseUserId(userId)));
    }

    private void requireAdmin(String role) {
        if (!"admin".equals(role)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }

    private Long parseUserId(String value) {
        try {
            long userId = Long.parseLong(value);
            if (userId <= 0) throw new NumberFormatException();
            return userId;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
    }
}

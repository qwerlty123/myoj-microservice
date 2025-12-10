package com.qwerlty.myojbackendaiservice.controller;

import com.qwerlty.myojbackendaiservice.common.BaseResponse;
import com.qwerlty.myojbackendaiservice.common.ErrorCode;
import com.qwerlty.myojbackendaiservice.common.ResultUtils;
import com.qwerlty.myojbackendaiservice.exception.BusinessException;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemDraftTaskRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityReviewTaskRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.TestCaseTaskRequest;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationTaskPageVO;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationTaskVO;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationQuotaVO;
import com.qwerlty.myojbackendaiservice.service.GenerationTaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean publicEnabled;

    public GenerationTaskController(GenerationTaskService taskService,
                                    @Value("${myoj.ai.generation.public-enabled:false}") boolean publicEnabled) {
        this.taskService = taskService;
        this.publicEnabled = publicEnabled;
    }

    @PostMapping("/problem-drafts")
    public BaseResponse<GenerationTaskVO> createProblemDraft(
            @Valid @RequestBody ProblemDraftTaskRequest request,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-user-Id") String userId,
            @RequestHeader("X-user-Role") String role) {
        return create(AuthoringTaskType.PROBLEM_DRAFT, request, idempotencyKey, userId, role);
    }

    @PostMapping("/test-cases")
    public BaseResponse<GenerationTaskVO> createTestCases(
            @Valid @RequestBody TestCaseTaskRequest request,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-user-Id") String userId,
            @RequestHeader("X-user-Role") String role) {
        return create(AuthoringTaskType.TEST_CASES, request, idempotencyKey, userId, role);
    }

    @PostMapping("/quality-reviews")
    public BaseResponse<GenerationTaskVO> createQualityReview(
            @Valid @RequestBody QualityReviewTaskRequest request,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-user-Id") String userId,
            @RequestHeader("X-user-Role") String role) {
        return create(AuthoringTaskType.QUALITY_REVIEW, request, idempotencyKey, userId, role);
    }

    @GetMapping("/{taskId}")
    public BaseResponse<GenerationTaskVO> get(
            @PathVariable @Positive Long taskId,
            @RequestHeader("X-user-Id") String userId,
            @RequestHeader("X-user-Role") String role) {
        requireUser(role);
        GenerationTaskVO task = taskService.get(taskId, parseUserId(userId), role);
        log.debug("[AI_GENERATION] task polled taskId={} status={} stage={} progress={}",
                taskId, task.getStatus(), task.getStage(), task.getProgress());
        return ResultUtils.success(task);
    }

    @GetMapping
    public BaseResponse<GenerationTaskPageVO> history(
            @RequestParam(defaultValue = "1") @Positive int current,
            @RequestParam(defaultValue = "10") @Positive int pageSize,
            @RequestParam(required = false) String type,
            @RequestHeader("X-user-Id") String userId,
            @RequestHeader("X-user-Role") String role) {
        requireUser(role);
        AuthoringTaskType taskType = type == null || type.isBlank() ? null : parseType(type);
        if (!"admin".equals(role) && taskType == AuthoringTaskType.QUALITY_REVIEW) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        return ResultUtils.success(taskService.history(parseUserId(userId), role, current, pageSize, taskType));
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
        requireUser(role);
        log.info("[AI_GENERATION] cancel request received taskId={}", taskId);
        return ResultUtils.success(taskService.cancel(taskId, parseUserId(userId)));
    }

    private void requireAdmin(String role) {
        if (!"admin".equals(role)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }

    private void requireUser(String role) {
        if (!"admin".equals(role) && !"user".equals(role)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }

    @GetMapping("/quota")
    public BaseResponse<GenerationQuotaVO> quota(
            @RequestHeader("X-user-Id") String userId,
            @RequestHeader("X-user-Role") String role) {
        requireUser(role);
        return ResultUtils.success(taskService.quota(parseUserId(userId), role));
    }

    private BaseResponse<GenerationTaskVO> create(AuthoringTaskType type,
                                                   com.qwerlty.myojbackendaiservice.generation.workflow.AuthoringRequest request,
                                                   String idempotencyKey,
                                                   String userId,
                                                   String role) {
        requireUser(role);
        if (type != AuthoringTaskType.QUALITY_REVIEW && !"admin".equals(role) && !publicEnabled) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "AI 题目创作尚未对外开放");
        }
        if (type == AuthoringTaskType.QUALITY_REVIEW) {
            requireAdmin(role);
        }
        Long parsedUserId = parseUserId(userId);
        log.info("[AI_GENERATION] create request received userId={} type={}", parsedUserId, type);
        GenerationTaskVO task = taskService.create(type, request, parsedUserId, role, idempotencyKey);
        return ResultUtils.success(task);
    }

    private AuthoringTaskType parseType(String value) {
        try {
            return AuthoringTaskType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "任务类型不合法");
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

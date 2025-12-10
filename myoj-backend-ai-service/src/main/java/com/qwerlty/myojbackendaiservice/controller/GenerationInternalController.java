package com.qwerlty.myojbackendaiservice.controller;

import com.qwerlty.myojbackendaiservice.common.BaseResponse;
import com.qwerlty.myojbackendaiservice.common.ErrorCode;
import com.qwerlty.myojbackendaiservice.common.ResultUtils;
import com.qwerlty.myojbackendaiservice.exception.BusinessException;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ArtifactValidationRequest;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ArtifactValidationResult;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityReviewTaskRequest;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.model.vo.GenerationTaskVO;
import com.qwerlty.myojbackendaiservice.service.GenerationTaskService;
import jakarta.validation.Valid;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/internal/generation")
public class GenerationInternalController {
    private final GenerationTaskService taskService;
    private final String internalToken;

    public GenerationInternalController(GenerationTaskService taskService,
                                        @Value("${myoj.ai.internal-token}") String internalToken) {
        this.taskService = taskService;
        this.internalToken = internalToken;
    }

    @PostMapping("/artifacts/validate")
    public BaseResponse<ArtifactValidationResult> validateArtifact(
            @Valid @RequestBody ArtifactValidationRequest request,
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String token) {
        requireInternal(token);
        return ResultUtils.success(taskService.validateArtifact(request));
    }

    @PostMapping("/quality-reviews")
    public BaseResponse<GenerationTaskVO> createQualityReview(
            @Valid @RequestBody QualityReviewTaskRequest request,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Actor-User-Id") Long actorUserId,
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String token) {
        requireInternal(token);
        return ResultUtils.success(taskService.create(AuthoringTaskType.QUALITY_REVIEW,
                request, actorUserId, "admin", idempotencyKey));
    }

    private void requireInternal(String actual) {
        if (StringUtils.isBlank(internalToken) || StringUtils.isBlank(actual)
                || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "内部调用凭证无效");
        }
    }
}

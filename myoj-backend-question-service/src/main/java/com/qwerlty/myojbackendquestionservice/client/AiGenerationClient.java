package com.qwerlty.myojbackendquestionservice.client;

import com.qwerlty.myojbackendcommon.common.BaseResponse;
import com.qwerlty.myojbackendquestionservice.model.dto.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "myoj-backend-ai-service", path = "/api/ai/internal/generation")
public interface AiGenerationClient {
    @PostMapping("/artifacts/validate")
    BaseResponse<AiArtifactValidationResponse> validateArtifact(
            @RequestBody AiArtifactValidationRequest request,
            @RequestHeader("X-Internal-Service-Token") String internalToken);

    @PostMapping("/quality-reviews")
    BaseResponse<AiGenerationTaskResponse> createQualityReview(
            @RequestBody AiQualityReviewCreateRequest request,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Actor-User-Id") Long actorUserId,
            @RequestHeader("X-Internal-Service-Token") String internalToken);
}

package com.qwerlty.myojbackendaiservice.client;

import com.qwerlty.myojbackendaiservice.common.BaseResponse;
import com.qwerlty.myojbackendaiservice.model.dto.AiSubmissionContextDTO;
import com.qwerlty.myojbackendaiservice.model.dto.AiSubmissionHistoryDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemSourceDraft;

@FeignClient(name = "myoj-backend-question-service", path = "/api/question/inner")
public interface QuestionServiceClient {

    @GetMapping("/ai/submission/context")
    BaseResponse<AiSubmissionContextDTO> getSubmissionContext(
            @RequestParam("submissionId") long submissionId,
            @RequestParam("userId") long userId,
            @RequestHeader("X-Internal-Service-Token") String internalToken);

    @GetMapping("/ai/submission/history")
    BaseResponse<List<AiSubmissionHistoryDTO>> getSubmissionHistory(
            @RequestParam("submissionId") long submissionId,
            @RequestParam("userId") long userId,
            @RequestParam("limit") int limit,
            @RequestHeader("X-Internal-Service-Token") String internalToken);

    @GetMapping("/ai/review-submission/snapshot")
    BaseResponse<ProblemSourceDraft> getReviewSubmissionSnapshot(
            @RequestParam("submissionId") long submissionId,
            @RequestHeader("X-Internal-Service-Token") String internalToken);
}

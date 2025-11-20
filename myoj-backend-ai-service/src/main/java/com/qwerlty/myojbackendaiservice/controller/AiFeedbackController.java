package com.qwerlty.myojbackendaiservice.controller;

import com.qwerlty.myojbackendaiservice.common.BaseResponse;
import com.qwerlty.myojbackendaiservice.common.ErrorCode;
import com.qwerlty.myojbackendaiservice.common.ResultUtils;
import com.qwerlty.myojbackendaiservice.exception.BusinessException;
import com.qwerlty.myojbackendaiservice.model.dto.AiFeedbackAddRequest;
import com.qwerlty.myojbackendaiservice.model.vo.AiFeedbackTaskVO;
import com.qwerlty.myojbackendaiservice.model.vo.AiFeedbackPageVO;
import com.qwerlty.myojbackendaiservice.service.AiFeedbackService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
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
@RequestMapping("/feedback")
@Validated
public class AiFeedbackController {

    private final AiFeedbackService feedbackService;

    public AiFeedbackController(AiFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping
    public BaseResponse<AiFeedbackTaskVO> create(
            @Valid @RequestBody AiFeedbackAddRequest request,
            @RequestHeader("X-user-Id") String userIdHeader) {
        return ResultUtils.success(feedbackService.createTask(
                request.getSubmissionId(), parseUserId(userIdHeader)));
    }

    @GetMapping("/{taskId}")
    public BaseResponse<AiFeedbackTaskVO> getTask(
            @PathVariable @Positive Long taskId,
            @RequestHeader("X-user-Id") String userIdHeader) {
        return ResultUtils.success(feedbackService.getTask(taskId, parseUserId(userIdHeader)));
    }

    @GetMapping("/submission/{submissionId}/latest")
    public BaseResponse<AiFeedbackTaskVO> getLatest(
            @PathVariable @Positive Long submissionId,
            @RequestHeader("X-user-Id") String userIdHeader) {
        return ResultUtils.success(feedbackService.getLatestBySubmission(
                submissionId, parseUserId(userIdHeader)));
    }

    @GetMapping("/history")
    public BaseResponse<AiFeedbackPageVO> getHistory(
            @RequestParam(required = false) @Positive Long submissionId,
            @RequestParam(defaultValue = "1") @Positive int current,
            @RequestParam(defaultValue = "10") @Positive int pageSize,
            @RequestHeader("X-user-Id") String userIdHeader) {
        return ResultUtils.success(feedbackService.getHistory(
                parseUserId(userIdHeader), submissionId, current, pageSize));
    }

    private Long parseUserId(String value) {
        try {
            long userId = Long.parseLong(value);
            if (userId <= 0) {
                throw new NumberFormatException("not positive");
            }
            return userId;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
    }
}

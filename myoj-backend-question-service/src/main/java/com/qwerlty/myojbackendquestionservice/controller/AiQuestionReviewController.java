package com.qwerlty.myojbackendquestionservice.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qwerlty.myojbackendcommon.annotation.AuthCheck;
import com.qwerlty.myojbackendcommon.common.BaseResponse;
import com.qwerlty.myojbackendcommon.common.ErrorCode;
import com.qwerlty.myojbackendcommon.common.ResultUtils;
import com.qwerlty.myojbackendcommon.constant.UserConstant;
import com.qwerlty.myojbackendcommon.exception.BusinessException;
import com.qwerlty.myojbackendmodel.model.entity.User;
import com.qwerlty.myojbackendquestionservice.model.dto.AiQuestionRejectRequest;
import com.qwerlty.myojbackendquestionservice.model.dto.AiQuestionSubmissionRequest;
import com.qwerlty.myojbackendquestionservice.model.vo.AiQuestionReviewSubmissionVO;
import com.qwerlty.myojbackendquestionservice.service.AiQuestionReviewService;
import com.qwerlty.myojbackendserviceclient.client.UserFeignClient;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/ai-submissions")
public class AiQuestionReviewController {
    @Resource
    private AiQuestionReviewService reviewService;
    @Resource
    private UserFeignClient userFeignClient;

    @PostMapping
    public BaseResponse<AiQuestionReviewSubmissionVO> submit(
            @RequestBody AiQuestionSubmissionRequest request, HttpServletRequest servletRequest) {
        User user = userFeignClient.getLoginUser(servletRequest);
        return ResultUtils.success(reviewService.submit(user.getId(), request));
    }

    @PostMapping("/{id}/resubmit")
    public BaseResponse<AiQuestionReviewSubmissionVO> resubmit(
            @PathVariable Long id, @RequestBody AiQuestionSubmissionRequest request,
            HttpServletRequest servletRequest) {
        if (request == null) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        request.setParentSubmissionId(id);
        User user = userFeignClient.getLoginUser(servletRequest);
        return ResultUtils.success(reviewService.submit(user.getId(), request));
    }

    @GetMapping("/my")
    public BaseResponse<Page<AiQuestionReviewSubmissionVO>> mine(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int pageSize,
            HttpServletRequest request) {
        User user = userFeignClient.getLoginUser(request);
        return ResultUtils.success(reviewService.listMine(user.getId(), current, pageSize));
    }

    @GetMapping("/{id}")
    public BaseResponse<AiQuestionReviewSubmissionVO> get(@PathVariable Long id, HttpServletRequest request) {
        User user = userFeignClient.getLoginUser(request);
        boolean admin = userFeignClient.isAdmin(user);
        return ResultUtils.success(reviewService.get(id, user.getId(), admin));
    }

    @PostMapping("/{id}/withdraw")
    public BaseResponse<AiQuestionReviewSubmissionVO> withdraw(@PathVariable Long id, HttpServletRequest request) {
        User user = userFeignClient.getLoginUser(request);
        return ResultUtils.success(reviewService.withdraw(id, user.getId()));
    }

    @GetMapping("/admin/list")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<AiQuestionReviewSubmissionVO>> adminList(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ResultUtils.success(reviewService.listAdmin(status, current, pageSize));
    }

    @PostMapping("/{id}/quality-review")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AiQuestionReviewSubmissionVO> qualityReview(
            @PathVariable Long id, HttpServletRequest request) {
        User reviewer = userFeignClient.getLoginUser(request);
        return ResultUtils.success(reviewService.startQualityReview(id, reviewer.getId()));
    }

    @PostMapping("/{id}/approve")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> approve(@PathVariable Long id, HttpServletRequest request) {
        User reviewer = userFeignClient.getLoginUser(request);
        return ResultUtils.success(reviewService.approve(id, reviewer.getId()));
    }

    @PostMapping("/{id}/reject")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AiQuestionReviewSubmissionVO> reject(
            @PathVariable Long id, @RequestBody AiQuestionRejectRequest body,
            HttpServletRequest request) {
        User reviewer = userFeignClient.getLoginUser(request);
        return ResultUtils.success(reviewService.reject(id, reviewer.getId(), body == null ? null : body.getReason()));
    }
}

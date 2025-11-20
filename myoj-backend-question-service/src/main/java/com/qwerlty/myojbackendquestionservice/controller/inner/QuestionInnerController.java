package com.qwerlty.myojbackendquestionservice.controller.inner;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.qwerlty.myojbackendcommon.common.BaseResponse;
import com.qwerlty.myojbackendcommon.common.ErrorCode;
import com.qwerlty.myojbackendcommon.common.ResultUtils;
import com.qwerlty.myojbackendcommon.exception.BusinessException;
import com.qwerlty.myojbackendmodel.model.dto.questionsubmit.QuestionSubmitQueryDTO;
import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskClaimRequest;
import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskCompleteRequest;
import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskRetryRequest;
import com.qwerlty.myojbackendmodel.model.entity.Question;
import com.qwerlty.myojbackendmodel.model.entity.QuestionSubmit;
import com.qwerlty.myojbackendmodel.model.enums.QuestionSubmitStatusEnum;
import com.qwerlty.myojbackendquestionservice.model.dto.AiSubmissionContextDTO;
import com.qwerlty.myojbackendquestionservice.model.dto.AiSubmissionHistoryDTO;
import com.qwerlty.myojbackendquestionservice.service.QuestionService;
import com.qwerlty.myojbackendquestionservice.service.JudgeTaskCoordinator;
import com.qwerlty.myojbackendquestionservice.service.QuestionSubmitService;
import com.qwerlty.myojbackendserviceclient.client.QuestionFeignClient;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 该服务仅内部调用，不是给前端的
 */
@RestController
@RequestMapping("/inner")
public class QuestionInnerController implements QuestionFeignClient {

    private static final int MAX_AI_HISTORY_SIZE = 3;

    @Resource
    private QuestionService questionService;

    @Resource
    private QuestionSubmitService questionSubmitService;

    @Resource
    private JudgeTaskCoordinator judgeTaskCoordinator;

    @Value("${myoj.ai.internal-token:}")
    private String aiInternalToken;

    /** 为 AI Service 提供当前提交的脱敏上下文。 */
    @GetMapping("/ai/submission/context")
    public BaseResponse<AiSubmissionContextDTO> getAiSubmissionContext(
            @RequestParam("submissionId") long submissionId,
            @RequestParam("userId") long userId,
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken) {
        checkAiInternalToken(internalToken);
        QuestionSubmit submission = getOwnedTerminalSubmission(submissionId, userId);
        Question question = questionService.getById(submission.getQuestionId());
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }

        AiSubmissionContextDTO context = new AiSubmissionContextDTO();
        context.setSubmissionId(submission.getId());
        context.setQuestionId(question.getId());
        context.setTitle(question.getTitle());
        context.setContent(question.getContent());
        context.setTags(parseTags(question.getTags()));
        context.setDifficulty(question.getDifficulty());
        context.setCode(submission.getCode());
        context.setLanguage(submission.getLanguage());
        context.setJudgeInfo(submission.getJudgeInfo());
        context.setStatus(submission.getStatus());
        context.setLastError(submission.getLastError());
        context.setCreateTime(submission.getCreateTime());
        return ResultUtils.success(context);
    }

    /** 返回当前用户、当前题目最近的历史终态提交，且不包含当前提交。 */
    @GetMapping("/ai/submission/history")
    public BaseResponse<List<AiSubmissionHistoryDTO>> getAiSubmissionHistory(
            @RequestParam("submissionId") long submissionId,
            @RequestParam("userId") long userId,
            @RequestParam(value = "limit", defaultValue = "3") int limit,
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken) {
        checkAiInternalToken(internalToken);
        QuestionSubmit current = getOwnedTerminalSubmission(submissionId, userId);
        int safeLimit = Math.max(1, Math.min(limit, MAX_AI_HISTORY_SIZE));

        QueryWrapper<QuestionSubmit> wrapper = new QueryWrapper<>();
        wrapper.eq("userId", userId)
                .eq("questionId", current.getQuestionId())
                .ne("id", current.getId())
                .in("status", QuestionSubmitStatusEnum.SUCCEED.getValue(),
                        QuestionSubmitStatusEnum.FAILED.getValue())
                .orderByDesc("createTime")
                .last("limit " + safeLimit);

        List<QuestionSubmit> submissions = questionSubmitService.list(wrapper);
        List<AiSubmissionHistoryDTO> result = new ArrayList<>();
        for (QuestionSubmit submission : submissions) {
            AiSubmissionHistoryDTO item = new AiSubmissionHistoryDTO();
            item.setSubmissionId(submission.getId());
            item.setCode(submission.getCode());
            item.setLanguage(submission.getLanguage());
            item.setJudgeInfo(submission.getJudgeInfo());
            item.setStatus(submission.getStatus());
            item.setLastError(submission.getLastError());
            item.setCreateTime(submission.getCreateTime());
            result.add(item);
        }
        return ResultUtils.success(result);
    }

    private QuestionSubmit getOwnedTerminalSubmission(long submissionId, long userId) {
        if (submissionId <= 0 || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        QuestionSubmit submission = questionSubmitService.getById(submissionId);
        if (submission == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "提交记录不存在");
        }
        if (!Long.valueOf(userId).equals(submission.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该提交");
        }
        Integer status = submission.getStatus();
        boolean terminal = QuestionSubmitStatusEnum.SUCCEED.getValue().equals(status)
                || QuestionSubmitStatusEnum.FAILED.getValue().equals(status);
        if (!terminal) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "判题尚未结束");
        }
        return submission;
    }

    private void checkAiInternalToken(String actualToken) {
        if (StringUtils.isBlank(aiInternalToken) || StringUtils.isBlank(actualToken)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "内部调用凭证无效");
        }
        boolean matches = MessageDigest.isEqual(
                aiInternalToken.getBytes(StandardCharsets.UTF_8),
                actualToken.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "内部调用凭证无效");
        }
    }

    private List<String> parseTags(String tags) {
        if (StringUtils.isBlank(tags)) {
            return Collections.emptyList();
        }
        try {
            return JSONUtil.toList(tags, String.class);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    /**
     * 根据查询条件批量获取题目提交列表
     *
     * @param questionSubmitQueryDTO
     * @return
     */
    @Override
    @PostMapping("/question_submit/list")
    public List<QuestionSubmit> list(@RequestBody QuestionSubmitQueryDTO questionSubmitQueryDTO) {
        QueryWrapper<QuestionSubmit> submitQueryWrapper = new QueryWrapper<>();
        submitQueryWrapper.eq("userId", questionSubmitQueryDTO.getUserId());
        if (questionSubmitQueryDTO.getQuestionId()!=null){
            submitQueryWrapper.eq("questionId", questionSubmitQueryDTO.getQuestionId());
        }
        submitQueryWrapper.orderByDesc("createTime", "id");
        return questionSubmitService.list(submitQueryWrapper);
    }

    /**
     * 根据查询条件获取题目
     *
     * @param questionId
     * @return
     */
    @Override
    @GetMapping("/get/one")
    public Question getOne(@RequestParam("questionId") long questionId) {
        return questionService.getOne(new QueryWrapper<Question>().eq("id", questionId));
    }

    /**
     * 根据id获取题目提交记录
     *
     * @param questionSubmitId
     * @return
     */
    @Override
    @GetMapping("/question_submit/get/id")
    public QuestionSubmit getQuestionSubmitById(@RequestParam("questionSubmitId") long questionSubmitId) {
        return questionSubmitService.getById(questionSubmitId);
    }

    @Override
    @GetMapping("/get/id")
    public Question getQuestionById(@RequestParam("questionId") long questionId) {
        return questionService.getById(questionId);
    }

    @Override
    @PostMapping("/question_submit/judge/claim")
    public Boolean claimJudgeTask(@RequestBody JudgeTaskClaimRequest request) {
        if (!validAttempt(request == null ? null : request.getSubmissionId(),
                request == null ? null : request.getJudgeAttempt())) {
            return false;
        }
        return judgeTaskCoordinator.claim(request.getSubmissionId(), request.getJudgeAttempt());
    }

    @Override
    @PostMapping("/question_submit/judge/complete")
    public Boolean completeJudgeTask(@RequestBody JudgeTaskCompleteRequest request) {
        if (request == null || !validAttempt(request.getSubmissionId(), request.getJudgeAttempt())) {
            return false;
        }
        return judgeTaskCoordinator.complete(request);
    }

    @Override
    @PostMapping("/question_submit/judge/retry")
    public Boolean retryJudgeTask(@RequestBody JudgeTaskRetryRequest request) {
        if (request == null || !validAttempt(request.getSubmissionId(), request.getJudgeAttempt())) {
            return false;
        }
        return judgeTaskCoordinator.scheduleRetry(
                request.getSubmissionId(), request.getJudgeAttempt(), request.getLastError());
    }

    private boolean validAttempt(Long submissionId, Integer judgeAttempt) {
        return submissionId != null && submissionId > 0 && judgeAttempt != null && judgeAttempt > 0;
    }
    @Override
    @PostMapping("/question/update/id")
    public Boolean updateQuestionById(@RequestBody Question question) {
        return questionService.updateById(question);
    }
}

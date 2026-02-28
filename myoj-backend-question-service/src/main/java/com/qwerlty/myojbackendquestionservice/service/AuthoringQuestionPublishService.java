package com.qwerlty.myojbackendquestionservice.service;

import cn.hutool.json.JSONUtil;
import com.qwerlty.myojbackendcommon.common.ErrorCode;
import com.qwerlty.myojbackendcommon.exception.BusinessException;
import com.qwerlty.myojbackendmodel.model.entity.Question;
import com.qwerlty.myojbackendquestionservice.mapper.AiQuestionPublishMapper;
import com.qwerlty.myojbackendquestionservice.model.AiQuestionPublishRecord;
import com.qwerlty.myojbackendquestionservice.model.AuthoringPublishRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthoringQuestionPublishService {

    private final AiQuestionPublishMapper publishMapper;
    private final QuestionService questionService;

    public AuthoringQuestionPublishService(AiQuestionPublishMapper publishMapper,
                                           QuestionService questionService) {
        this.publishMapper = publishMapper;
        this.questionService = questionService;
    }

    @Transactional(rollbackFor = Exception.class)
    public long publish(AuthoringPublishRequest request) {
        validateRequest(request);
        publishMapper.claim(
                request.getIdempotencyKey(),
                request.getSourceTaskId(),
                request.getReviewerId(),
                request.getPayloadHash()
        );
        AiQuestionPublishRecord record = publishMapper.findForUpdate(request.getIdempotencyKey());
        if (record == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "无法建立 AI 发布幂等记录");
        }
        if (!request.getPayloadHash().equals(record.getPayloadHash())
                || !request.getSourceTaskId().equals(record.getSourceTaskId())
                || !request.getReviewerId().equals(record.getReviewerId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "相同幂等键不能更改任务、审核人或题目内容");
        }
        if (record.getQuestionId() != null) return record.getQuestionId();

        Question question = toQuestion(request);
        questionService.validQuestion(question, true);
        if (!questionService.save(question) || question.getId() == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 审核题目发布失败");
        }
        if (publishMapper.complete(request.getIdempotencyKey(), question.getId()) != 1) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 发布幂等结果保存失败");
        }
        return question.getId();
    }

    private static Question toQuestion(AuthoringPublishRequest request) {
        Question question = new Question();
        question.setTitle(request.getTitle());
        question.setDifficulty(request.getDifficulty());
        question.setContent(request.getContent());
        question.setTags(JSONUtil.toJsonStr(request.getTags()));
        question.setAnswer(request.getAnswer());
        question.setJudgeCase(JSONUtil.toJsonStr(request.getJudgeCase()));
        question.setJudgeConfig(JSONUtil.toJsonStr(request.getJudgeConfig()));
        question.setUserId(request.getReviewerId());
        question.setFavourNum(0);
        question.setThumbNum(0);
        return question;
    }

    private static void validateRequest(AuthoringPublishRequest request) {
        if (request == null
                || StringUtils.isBlank(request.getIdempotencyKey())
                || request.getIdempotencyKey().length() > 128
                || request.getSourceTaskId() == null || request.getSourceTaskId() <= 0
                || request.getReviewerId() == null || request.getReviewerId() <= 0
                || !isSha256(request.getPayloadHash())
                || request.getTags() == null
                || request.getJudgeCase() == null
                || request.getJudgeConfig() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "AI 发布请求不完整");
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}

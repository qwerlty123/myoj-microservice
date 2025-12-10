package com.qwerlty.myojbackendquestionservice.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qwerlty.myojbackendcommon.common.BaseResponse;
import com.qwerlty.myojbackendcommon.common.ErrorCode;
import com.qwerlty.myojbackendcommon.exception.BusinessException;
import com.qwerlty.myojbackendcommon.exception.ThrowUtils;
import com.qwerlty.myojbackendmodel.model.dto.question.JudgeCase;
import com.qwerlty.myojbackendmodel.model.dto.question.JudgeConfig;
import com.qwerlty.myojbackendmodel.model.dto.question.QuestionAddRequest;
import com.qwerlty.myojbackendmodel.model.entity.Question;
import com.qwerlty.myojbackendmodel.model.enums.QuestionDifficultyEnum;
import com.qwerlty.myojbackendquestionservice.client.AiGenerationClient;
import com.qwerlty.myojbackendquestionservice.mapper.AiQuestionReviewSubmissionMapper;
import com.qwerlty.myojbackendquestionservice.model.dto.*;
import com.qwerlty.myojbackendquestionservice.model.entity.AiQuestionReviewSubmission;
import com.qwerlty.myojbackendquestionservice.model.enums.AiQuestionReviewStatus;
import com.qwerlty.myojbackendquestionservice.model.vo.AiQuestionReviewSubmissionVO;
import com.qwerlty.myojbackendquestionservice.service.AiQuestionReviewService;
import com.qwerlty.myojbackendquestionservice.service.QuestionService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AiQuestionReviewServiceImpl implements AiQuestionReviewService {
    @Resource
    private AiQuestionReviewSubmissionMapper mapper;
    @Resource
    private QuestionService questionService;
    @Resource
    private AiGenerationClient aiClient;

    @Value("${myoj.ai.internal-token:}")
    private String internalToken;

    @Override
    public AiQuestionReviewSubmissionVO submit(Long userId, AiQuestionSubmissionRequest request) {
        if (userId == null || userId <= 0 || request == null || request.getSnapshot() == null
                || request.getTestCasesTaskId() == null || request.getTestCasesTaskId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Question snapshotQuestion = toQuestion(request.getSnapshot(), userId);
        questionService.validQuestion(snapshotQuestion, true);

        int version = 1;
        if (request.getParentSubmissionId() != null) {
            AiQuestionReviewSubmission parent = mapper.selectById(request.getParentSubmissionId());
            if (parent == null || !userId.equals(parent.getUserId())
                    || !AiQuestionReviewStatus.REJECTED.name().equals(parent.getStatus())) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "只能基于自己被拒绝的版本重新提交");
            }
            version = parent.getVersion() + 1;
        }

        BaseResponse<AiArtifactValidationResponse> validationResponse = aiClient.validateArtifact(
                new AiArtifactValidationRequest(userId, request.getTestCasesTaskId(), request.getSnapshot()),
                internalToken);
        AiArtifactValidationResponse validation = validationResponse == null ? null : validationResponse.getData();
        if (validation == null || !Boolean.TRUE.equals(validation.getValid())
                || StringUtils.isBlank(validation.getExecutionHash()) || validation.getSourceTaskId() == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 测试用例产物验证失败");
        }

        Date now = new Date();
        AiQuestionReviewSubmission submission = new AiQuestionReviewSubmission();
        submission.setUserId(userId);
        submission.setParentSubmissionId(request.getParentSubmissionId());
        submission.setVersion(version);
        submission.setStatus(AiQuestionReviewStatus.PENDING.name());
        submission.setSnapshotJson(JSONUtil.toJsonStr(request.getSnapshot()));
        submission.setExecutionHash(validation.getExecutionHash());
        submission.setProblemDraftTaskId(validation.getSourceTaskId());
        submission.setTestCasesTaskId(request.getTestCasesTaskId());
        submission.setCreateTime(now);
        submission.setUpdateTime(now);
        submission.setLockVersion(0);
        try {
            mapper.insert(submission);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该版本已提交，请刷新列表");
        }
        return toVO(submission);
    }

    @Override
    public Page<AiQuestionReviewSubmissionVO> listMine(Long userId, int current, int pageSize) {
        validatePage(current, pageSize);
        QueryWrapper<AiQuestionReviewSubmission> query = new QueryWrapper<AiQuestionReviewSubmission>()
                .eq("userId", userId).orderByDesc("createTime");
        return mapPage(mapper.selectPage(new Page<AiQuestionReviewSubmission>(current, pageSize), query));
    }

    @Override
    public Page<AiQuestionReviewSubmissionVO> listAdmin(String status, int current, int pageSize) {
        validatePage(current, pageSize);
        QueryWrapper<AiQuestionReviewSubmission> query = new QueryWrapper<AiQuestionReviewSubmission>()
                .orderByAsc("case when status = 'PENDING' then 0 else 1 end")
                .orderByDesc("createTime");
        if (StringUtils.isNotBlank(status)) {
            try { AiQuestionReviewStatus.valueOf(status); }
            catch (IllegalArgumentException exception) { throw new BusinessException(ErrorCode.PARAMS_ERROR); }
            query.eq("status", status);
        }
        return mapPage(mapper.selectPage(new Page<AiQuestionReviewSubmission>(current, pageSize), query));
    }

    @Override
    public AiQuestionReviewSubmissionVO get(Long id, Long actorId, boolean admin) {
        AiQuestionReviewSubmission submission = require(id);
        if (!admin && !actorId.equals(submission.getUserId())) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        return toVO(submission);
    }

    @Override
    public AiQuestionReviewSubmissionVO withdraw(Long id, Long userId) {
        if (mapper.withdraw(id, userId) <= 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "只有自己的待审核版本可以撤回");
        }
        return toVO(require(id));
    }

    @Override
    @Transactional
    public AiQuestionReviewSubmissionVO reject(Long id, Long reviewerId, String reason) {
        if (StringUtils.isBlank(reason) || reason.trim().length() > 1000) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "拒绝原因不能为空且不能超过 1000 字");
        }
        AiQuestionReviewSubmission locked = mapper.selectForUpdate(id);
        if (locked == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        if (AiQuestionReviewStatus.REJECTED.name().equals(locked.getStatus())) return toVO(locked);
        if (!AiQuestionReviewStatus.PENDING.name().equals(locked.getStatus())
                || mapper.reject(id, reviewerId, reason.trim()) <= 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "审核状态已变化");
        }
        return toVO(require(id));
    }

    @Override
    @Transactional
    public Long approve(Long id, Long reviewerId) {
        AiQuestionReviewSubmission locked = mapper.selectForUpdate(id);
        if (locked == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        if (AiQuestionReviewStatus.APPROVED.name().equals(locked.getStatus())) return locked.getPublishedQuestionId();
        if (!AiQuestionReviewStatus.PENDING.name().equals(locked.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "只有待审核版本可以通过");
        }
        QuestionAddRequest snapshot = parseSnapshot(locked.getSnapshotJson());
        Question question = toQuestion(snapshot, locked.getUserId());
        questionService.validQuestion(question, true);
        question.setFavourNum(0);
        question.setThumbNum(0);
        question.setSubmitNum(0);
        question.setAcceptedNum(0);
        ThrowUtils.throwIf(!questionService.save(question), ErrorCode.OPERATION_ERROR);
        if (mapper.approve(id, reviewerId, question.getId()) <= 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "审核状态已变化");
        }
        return question.getId();
    }

    @Override
    public AiQuestionReviewSubmissionVO startQualityReview(Long id, Long reviewerId) {
        AiQuestionReviewSubmission submission = require(id);
        if (!AiQuestionReviewStatus.PENDING.name().equals(submission.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "只有待审核版本可以启动 AI 质检");
        }
        if (submission.getQualityReviewTaskId() != null) return toVO(submission);
        String idempotencyKey = UUID.nameUUIDFromBytes(
                ("quality:" + id + ":" + submission.getVersion()).getBytes(StandardCharsets.UTF_8)).toString();
        BaseResponse<AiGenerationTaskResponse> response = aiClient.createQualityReview(
                new AiQualityReviewCreateRequest(id), idempotencyKey, reviewerId, internalToken);
        AiGenerationTaskResponse task = response == null ? null : response.getData();
        if (task == null || task.getTaskId() == null) {
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "AI 质检任务创建失败");
        }
        mapper.attachQualityTask(id, task.getTaskId());
        return toVO(require(id));
    }

    @Override
    public QuestionAddRequest authoritativeSnapshot(Long id) {
        AiQuestionReviewSubmission submission = require(id);
        if (!AiQuestionReviewStatus.PENDING.name().equals(submission.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "审核快照当前不可质检");
        }
        return parseSnapshot(submission.getSnapshotJson());
    }

    private AiQuestionReviewSubmission require(Long id) {
        if (id == null || id <= 0) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        AiQuestionReviewSubmission value = mapper.selectById(id);
        if (value == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "AI 题目审核记录不存在");
        return value;
    }

    private Question toQuestion(QuestionAddRequest request, Long userId) {
        Question question = new Question();
        question.setTitle(request.getTitle());
        question.setDifficulty(QuestionDifficultyEnum.getEnumByValue(request.getDifficulty()) == null
                ? 0 : request.getDifficulty());
        question.setContent(request.getContent());
        question.setTags(JSONUtil.toJsonStr(request.getTags()));
        question.setAnswer(request.getAnswer());
        List<JudgeCase> cases = request.getJudgeCase();
        JudgeConfig config = request.getJudgeConfig();
        question.setJudgeCase(JSONUtil.toJsonStr(cases));
        question.setJudgeConfig(JSONUtil.toJsonStr(config));
        question.setUserId(userId);
        return question;
    }

    private QuestionAddRequest parseSnapshot(String json) {
        try { return JSONUtil.toBean(json, QuestionAddRequest.class); }
        catch (RuntimeException exception) { throw new BusinessException(ErrorCode.OPERATION_ERROR, "审核快照损坏"); }
    }

    private AiQuestionReviewSubmissionVO toVO(AiQuestionReviewSubmission source) {
        AiQuestionReviewSubmissionVO target = new AiQuestionReviewSubmissionVO();
        target.setId(source.getId());
        target.setUserId(source.getUserId());
        target.setParentSubmissionId(source.getParentSubmissionId());
        target.setVersion(source.getVersion());
        target.setStatus(source.getStatus());
        target.setSnapshot(parseSnapshot(source.getSnapshotJson()));
        target.setExecutionHash(source.getExecutionHash());
        target.setProblemDraftTaskId(source.getProblemDraftTaskId());
        target.setTestCasesTaskId(source.getTestCasesTaskId());
        target.setQualityReviewTaskId(source.getQualityReviewTaskId());
        target.setReviewerId(source.getReviewerId());
        target.setReviewReason(source.getReviewReason());
        target.setPublishedQuestionId(source.getPublishedQuestionId());
        target.setReviewTime(source.getReviewTime());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
        return target;
    }

    private Page<AiQuestionReviewSubmissionVO> mapPage(Page<AiQuestionReviewSubmission> source) {
        Page<AiQuestionReviewSubmissionVO> target = new Page<AiQuestionReviewSubmissionVO>(
                source.getCurrent(), source.getSize(), source.getTotal());
        target.setRecords(source.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return target;
    }

    private void validatePage(int current, int pageSize) {
        if (current <= 0 || pageSize <= 0 || pageSize > 50) throw new BusinessException(ErrorCode.PARAMS_ERROR);
    }
}

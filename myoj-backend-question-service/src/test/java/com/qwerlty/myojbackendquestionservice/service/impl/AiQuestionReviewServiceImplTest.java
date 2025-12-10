package com.qwerlty.myojbackendquestionservice.service.impl;

import cn.hutool.json.JSONUtil;
import com.qwerlty.myojbackendcommon.common.BaseResponse;
import com.qwerlty.myojbackendcommon.exception.BusinessException;
import com.qwerlty.myojbackendmodel.model.dto.question.JudgeCase;
import com.qwerlty.myojbackendmodel.model.dto.question.JudgeConfig;
import com.qwerlty.myojbackendmodel.model.dto.question.QuestionAddRequest;
import com.qwerlty.myojbackendmodel.model.entity.Question;
import com.qwerlty.myojbackendquestionservice.client.AiGenerationClient;
import com.qwerlty.myojbackendquestionservice.mapper.AiQuestionReviewSubmissionMapper;
import com.qwerlty.myojbackendquestionservice.model.dto.AiArtifactValidationResponse;
import com.qwerlty.myojbackendquestionservice.model.dto.AiQuestionSubmissionRequest;
import com.qwerlty.myojbackendquestionservice.model.entity.AiQuestionReviewSubmission;
import com.qwerlty.myojbackendquestionservice.model.enums.AiQuestionReviewStatus;
import com.qwerlty.myojbackendquestionservice.model.vo.AiQuestionReviewSubmissionVO;
import com.qwerlty.myojbackendquestionservice.service.QuestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiQuestionReviewServiceImplTest {
    private AiQuestionReviewSubmissionMapper mapper;
    private QuestionService questionService;
    private AiGenerationClient aiClient;
    private AiQuestionReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(AiQuestionReviewSubmissionMapper.class);
        questionService = mock(QuestionService.class);
        aiClient = mock(AiGenerationClient.class);
        service = new AiQuestionReviewServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "questionService", questionService);
        ReflectionTestUtils.setField(service, "aiClient", aiClient);
        ReflectionTestUtils.setField(service, "internalToken", "internal-test-token");
    }

    @Test
    void verifiedArtifactCreatesAnImmutablePendingVersion() {
        AiArtifactValidationResponse validation = new AiArtifactValidationResponse();
        validation.setValid(true);
        validation.setSourceTaskId(21L);
        validation.setExecutionHash("hash-v1");
        when(aiClient.validateArtifact(any(), anyString()))
                .thenReturn(new BaseResponse<AiArtifactValidationResponse>(0, validation, "ok"));
        when(mapper.insert(any())).thenAnswer(invocation -> {
            AiQuestionReviewSubmission inserted = invocation.getArgument(0);
            inserted.setId(31L);
            return 1;
        });
        AiQuestionSubmissionRequest request = request();

        AiQuestionReviewSubmissionVO result = service.submit(7L, request);

        assertThat(result.getId()).isEqualTo(31L);
        assertThat(result.getStatus()).isEqualTo(AiQuestionReviewStatus.PENDING.name());
        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(result.getProblemDraftTaskId()).isEqualTo(21L);
        assertThat(result.getTestCasesTaskId()).isEqualTo(22L);
        assertThat(result.getExecutionHash()).isEqualTo("hash-v1");
        verify(questionService).validQuestion(any(Question.class), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void resubmissionRequiresAnOwnedRejectedParentAndCreatesANewVersion() {
        AiQuestionReviewSubmission parent = submission(40L, 8L, AiQuestionReviewStatus.REJECTED);
        parent.setVersion(2);
        when(mapper.selectById(40L)).thenReturn(parent);
        AiQuestionSubmissionRequest request = request();
        request.setParentSubmissionId(40L);

        assertThatThrownBy(() -> service.submit(7L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("自己被拒绝");
        verify(mapper, never()).insert(any());
    }

    @Test
    void approvalPublishesAsTheOriginalAuthorAndIsIdempotent() {
        AiQuestionReviewSubmission pending = submission(51L, 7L, AiQuestionReviewStatus.PENDING);
        when(mapper.selectForUpdate(51L)).thenReturn(pending);
        when(questionService.save(any(Question.class))).thenAnswer(invocation -> {
            Question question = invocation.getArgument(0);
            question.setId(61L);
            return true;
        });
        when(mapper.approve(51L, 99L, 61L)).thenReturn(1);

        assertThat(service.approve(51L, 99L)).isEqualTo(61L);
        verify(questionService).save(org.mockito.ArgumentMatchers.argThat(
                question -> Long.valueOf(7L).equals(question.getUserId())));

        AiQuestionReviewSubmission approved = submission(51L, 7L, AiQuestionReviewStatus.APPROVED);
        approved.setPublishedQuestionId(61L);
        when(mapper.selectForUpdate(51L)).thenReturn(approved);
        assertThat(service.approve(51L, 99L)).isEqualTo(61L);
    }

    @Test
    void rejectionRequiresAReasonAndNeverMutatesTheSnapshot() {
        AiQuestionReviewSubmission pending = submission(71L, 7L, AiQuestionReviewStatus.PENDING);
        when(mapper.selectForUpdate(71L)).thenReturn(pending);

        assertThatThrownBy(() -> service.reject(71L, 99L, " "))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).reject(anyLong(), anyLong(), anyString());
    }

    private AiQuestionSubmissionRequest request() {
        AiQuestionSubmissionRequest request = new AiQuestionSubmissionRequest();
        request.setTestCasesTaskId(22L);
        request.setSnapshot(snapshot());
        return request;
    }

    private AiQuestionReviewSubmission submission(Long id, Long userId, AiQuestionReviewStatus status) {
        AiQuestionReviewSubmission submission = new AiQuestionReviewSubmission();
        submission.setId(id);
        submission.setUserId(userId);
        submission.setVersion(1);
        submission.setStatus(status.name());
        submission.setSnapshotJson(JSONUtil.toJsonStr(snapshot()));
        return submission;
    }

    private QuestionAddRequest snapshot() {
        QuestionAddRequest request = new QuestionAddRequest();
        request.setTitle("窗口最大值");
        request.setDifficulty(1);
        request.setContent("## 题目描述\n内容");
        request.setTags(Collections.singletonList("队列"));
        request.setAnswer("单调队列");
        JudgeCase judgeCase = new JudgeCase();
        judgeCase.setInput("1\n");
        judgeCase.setOutput("1");
        request.setJudgeCase(Collections.singletonList(judgeCase));
        JudgeConfig config = new JudgeConfig();
        config.setTimeLimit(1000L);
        config.setMemoryLimit(262144L);
        config.setStackLimit(262144L);
        request.setJudgeConfig(config);
        return request;
    }
}

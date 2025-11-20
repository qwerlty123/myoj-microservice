package com.qwerlty.myojbackendquestionservice.controller.inner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.qwerlty.myojbackendcommon.common.BaseResponse;
import com.qwerlty.myojbackendcommon.exception.BusinessException;
import com.qwerlty.myojbackendmodel.model.dto.questionsubmit.QuestionSubmitQueryDTO;
import com.qwerlty.myojbackendmodel.model.entity.Question;
import com.qwerlty.myojbackendmodel.model.entity.QuestionSubmit;
import com.qwerlty.myojbackendmodel.model.enums.QuestionSubmitStatusEnum;
import com.qwerlty.myojbackendquestionservice.model.dto.AiSubmissionContextDTO;
import com.qwerlty.myojbackendquestionservice.service.QuestionService;
import com.qwerlty.myojbackendquestionservice.service.QuestionSubmitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestionInnerControllerAiTest {

    private QuestionInnerController controller;
    private QuestionService questionService;
    private QuestionSubmitService questionSubmitService;

    @BeforeEach
    void setUp() {
        controller = new QuestionInnerController();
        questionService = mock(QuestionService.class);
        questionSubmitService = mock(QuestionSubmitService.class);
        ReflectionTestUtils.setField(controller, "questionService", questionService);
        ReflectionTestUtils.setField(controller, "questionSubmitService", questionSubmitService);
        ReflectionTestUtils.setField(controller, "aiInternalToken", "internal-token");
    }

    @Test
    void contextContainsOnlyWhitelistedFieldsEvenWhenQuestionHasSecrets() throws Exception {
        QuestionSubmit submission = terminalSubmission(9L, 7L);
        Question question = new Question();
        question.setId(88L);
        question.setTitle("Two Sum");
        question.setContent("题目内容");
        question.setTags("[\"array\"]");
        question.setDifficulty(1);
        question.setAnswer("secret answer");
        question.setJudgeCase("secret judge cases");
        when(questionSubmitService.getById(9L)).thenReturn(submission);
        when(questionService.getById(88L)).thenReturn(question);

        BaseResponse<AiSubmissionContextDTO> response = controller.getAiSubmissionContext(
                9L, 7L, "internal-token");
        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writeValueAsString(response.getData());
        JsonNode node = objectMapper.readTree(json);

        assertThat(response.getCode()).isZero();
        assertThat(response.getData().getSubmissionId()).isEqualTo(9L);
        assertThat(json)
                .contains("user code")
                .doesNotContain("secret answer", "secret judge cases");
        assertThat(node.has("answer")).isFalse();
        assertThat(node.has("judgeCase")).isFalse();
    }

    @Test
    void otherUsersSubmissionIsRejected() {
        when(questionSubmitService.getById(9L)).thenReturn(terminalSubmission(9L, 8L));

        assertThatThrownBy(() -> controller.getAiSubmissionContext(9L, 7L, "internal-token"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void invalidInternalTokenIsRejectedBeforeDatabaseAccess() {
        assertThatThrownBy(() -> controller.getAiSubmissionContext(9L, 7L, "wrong-token"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void submissionListIsOrderedNewestFirst() {
        QuestionSubmitQueryDTO query = new QuestionSubmitQueryDTO();
        query.setUserId(7L);
        when(questionSubmitService.list(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        controller.list(query);

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(questionSubmitService).list(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .containsIgnoringCase("ORDER BY createTime DESC,id DESC");
    }

    private QuestionSubmit terminalSubmission(Long submissionId, Long userId) {
        QuestionSubmit submission = new QuestionSubmit();
        submission.setId(submissionId);
        submission.setUserId(userId);
        submission.setQuestionId(88L);
        submission.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
        submission.setCode("user code");
        submission.setLanguage("java");
        submission.setJudgeInfo("{\"message\":\"Wrong Answer\"}");
        submission.setLastError("wrong answer");
        submission.setCreateTime(new Date());
        return submission;
    }
}

package com.qwerlty.myojbackendquestionservice.service;

import com.qwerlty.myojbackendcommon.exception.BusinessException;
import com.qwerlty.myojbackendmodel.model.dto.question.JudgeCase;
import com.qwerlty.myojbackendmodel.model.dto.question.JudgeConfig;
import com.qwerlty.myojbackendmodel.model.entity.Question;
import com.qwerlty.myojbackendquestionservice.mapper.AiQuestionPublishMapper;
import com.qwerlty.myojbackendquestionservice.model.AiQuestionPublishRecord;
import com.qwerlty.myojbackendquestionservice.model.AuthoringPublishRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthoringQuestionPublishServiceTest {

    private AiQuestionPublishMapper publishMapper;
    private QuestionService questionService;
    private AuthoringQuestionPublishService service;

    @BeforeEach
    void setUp() {
        publishMapper = mock(AiQuestionPublishMapper.class);
        questionService = mock(QuestionService.class);
        service = new AuthoringQuestionPublishService(publishMapper, questionService);
    }

    @Test
    void createsQuestionAndCompletesTheIdempotencyRecordInOneTransaction() {
        AuthoringPublishRequest request = request();
        when(publishMapper.findForUpdate(request.getIdempotencyKey())).thenReturn(record(null, request));
        doAnswer(invocation -> {
            Question question = invocation.getArgument(0);
            question.setId(9001L);
            return true;
        }).when(questionService).save(any(Question.class));
        when(publishMapper.complete(request.getIdempotencyKey(), 9001L)).thenReturn(1);

        assertThat(service.publish(request)).isEqualTo(9001L);

        verify(questionService).validQuestion(any(Question.class), org.mockito.ArgumentMatchers.eq(true));
        verify(publishMapper).complete(request.getIdempotencyKey(), 9001L);
    }

    @Test
    void replayReturnsTheOriginalQuestionWithoutCreatingAnotherOne() {
        AuthoringPublishRequest request = request();
        when(publishMapper.findForUpdate(request.getIdempotencyKey())).thenReturn(record(9001L, request));

        assertThat(service.publish(request)).isEqualTo(9001L);

        verify(questionService, never()).save(any(Question.class));
    }

    @Test
    void rejectsReusingTheKeyForDifferentReviewedContent() {
        AuthoringPublishRequest request = request();
        AiQuestionPublishRecord record = record(9001L, request);
        record.setPayloadHash(repeat('b', 64));
        when(publishMapper.findForUpdate(request.getIdempotencyKey())).thenReturn(record);

        assertThatThrownBy(() -> service.publish(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("相同幂等键");
        verify(questionService, never()).save(any(Question.class));
    }

    @Test
    void rejectsReplayingAnApprovalAsAnotherReviewer() {
        AuthoringPublishRequest request = request();
        AiQuestionPublishRecord record = record(9001L, request);
        record.setReviewerId(8L);
        when(publishMapper.findForUpdate(request.getIdempotencyKey())).thenReturn(record);

        assertThatThrownBy(() -> service.publish(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("相同幂等键");
        verify(questionService, never()).save(any(Question.class));
    }

    private static AuthoringPublishRequest request() {
        AuthoringPublishRequest request = new AuthoringPublishRequest();
        request.setIdempotencyKey("ai-authoring-task-88-publish-v1");
        request.setSourceTaskId(88L);
        request.setReviewerId(7L);
        request.setPayloadHash(repeat('a', 64));
        request.setTitle("人工审核题目");
        request.setDifficulty(1);
        request.setContent("题目内容");
        request.setTags(Collections.singletonList("数组"));
        request.setAnswer("题解");
        JudgeCase judgeCase = new JudgeCase();
        judgeCase.setInput("1\n");
        judgeCase.setOutput("1\n");
        request.setJudgeCase(Collections.singletonList(judgeCase));
        JudgeConfig config = new JudgeConfig();
        config.setTimeLimit(1000L);
        config.setMemoryLimit(262144L);
        config.setStackLimit(65536L);
        request.setJudgeConfig(config);
        return request;
    }

    private static AiQuestionPublishRecord record(Long questionId, AuthoringPublishRequest request) {
        AiQuestionPublishRecord record = new AiQuestionPublishRecord();
        record.setIdempotencyKey(request.getIdempotencyKey());
        record.setSourceTaskId(request.getSourceTaskId());
        record.setReviewerId(request.getReviewerId());
        record.setPayloadHash(request.getPayloadHash());
        record.setQuestionId(questionId);
        return record;
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}

package com.qwerlty.myojbackendaiservice.tool;

import com.qwerlty.myojbackendaiservice.client.QuestionServiceClient;
import com.qwerlty.myojbackendaiservice.common.BaseResponse;
import com.qwerlty.myojbackendaiservice.model.dto.AiSubmissionContextDTO;
import com.qwerlty.myojbackendaiservice.model.dto.AiSubmissionHistoryDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmissionToolsTest {

    @Test
    void historyToolUsesTrustedIdsAndClampsModelControlledLimit() {
        QuestionServiceClient client = mock(QuestionServiceClient.class);
        when(client.getSubmissionHistory(9L, 7L, 3, "token"))
                .thenReturn(new BaseResponse<>(0, new ArrayList<>(), "ok"));
        SubmissionTools tools = new SubmissionTools(client, "token");
        ToolContext context = new ToolContext(Map.of(
                SubmissionTools.USER_ID, 7L,
                SubmissionTools.SUBMISSION_ID, 9L,
                SubmissionTools.CURRENT_TOOL_CALLED, new AtomicBoolean(true),
                SubmissionTools.TOOL_CALL_COUNT, new AtomicInteger()));

        List<AiSubmissionHistoryDTO> result = tools.getRecentAttempts(99, context);

        assertThat(result).isEmpty();
        verify(client).getSubmissionHistory(9L, 7L, 3, "token");
    }

    @Test
    void historyToolRejectsCallBeforeCurrentSubmissionTool() {
        SubmissionTools tools = new SubmissionTools(mock(QuestionServiceClient.class), "token");
        ToolContext context = new ToolContext(Map.of(
                SubmissionTools.USER_ID, 7L,
                SubmissionTools.SUBMISSION_ID, 9L,
                SubmissionTools.CURRENT_TOOL_CALLED, new AtomicBoolean(false),
                SubmissionTools.TOOL_CALL_COUNT, new AtomicInteger()));

        assertThatThrownBy(() -> tools.getRecentAttempts(1, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须先获取当前提交");
    }

    @Test
    void currentSubmissionToolReturnsCopyAndMarksMandatoryCall() {
        QuestionServiceClient client = mock(QuestionServiceClient.class);
        SubmissionTools tools = new SubmissionTools(client, "token");
        AiSubmissionContextDTO original = new AiSubmissionContextDTO();
        original.setSubmissionId(9L);
        original.setQuestionId(88L);
        original.setCode("x".repeat(30_100));
        AtomicBoolean called = new AtomicBoolean(false);
        AtomicInteger count = new AtomicInteger(0);
        ToolContext context = new ToolContext(Map.of(
                SubmissionTools.CURRENT_CONTEXT, original,
                SubmissionTools.CURRENT_TOOL_CALLED, called,
                SubmissionTools.TOOL_CALL_COUNT, count));

        AiSubmissionContextDTO returned = tools.getCurrentSubmission(context);

        assertThat(returned).isNotSameAs(original);
        assertThat(returned.getCode()).hasSizeLessThan(original.getCode().length());
        assertThat(called.get()).isTrue();
        assertThat(count).hasValue(1);
    }
}

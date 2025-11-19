package com.qwerlty.myojbackendaiservice.tool;

import com.qwerlty.myojbackendaiservice.client.QuestionServiceClient;
import com.qwerlty.myojbackendaiservice.common.BaseResponse;
import com.qwerlty.myojbackendaiservice.model.dto.AiSubmissionContextDTO;
import com.qwerlty.myojbackendaiservice.model.dto.AiSubmissionHistoryDTO;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SubmissionTools {

    public static final String USER_ID = "trustedUserId";
    public static final String SUBMISSION_ID = "trustedSubmissionId";
    public static final String CURRENT_CONTEXT = "currentSubmissionContext";
    public static final String CURRENT_TOOL_CALLED = "currentSubmissionToolCalled";
    public static final String TOOL_CALL_COUNT = "toolCallCount";

    private static final int MAX_CURRENT_CODE_LENGTH = 30_000;
    private static final int MAX_HISTORY_CODE_LENGTH = 12_000;

    private final QuestionServiceClient questionServiceClient;
    private final String internalToken;

    public SubmissionTools(QuestionServiceClient questionServiceClient,
                           @Value("${myoj.ai.internal-token}") String internalToken) {
        this.questionServiceClient = questionServiceClient;
        this.internalToken = internalToken;
    }

    @Tool(description = "获取当前用户本次提交的完整脱敏上下文。分析任务必须先调用本工具；不能指定用户或提交。")
    public AiSubmissionContextDTO getCurrentSubmission(ToolContext toolContext) {
        Object value = toolContext.getContext().get(CURRENT_CONTEXT);
        if (!(value instanceof AiSubmissionContextDTO context)) {
            throw new IllegalStateException("当前提交上下文不存在");
        }
        Object marker = toolContext.getContext().get(CURRENT_TOOL_CALLED);
        if (marker instanceof AtomicBoolean called) {
            called.set(true);
        }
        incrementToolCount(toolContext);
        return sanitizeContext(context, MAX_CURRENT_CODE_LENGTH);
    }

    @Tool(description = "获取当前用户在同一道题最近的终态提交，用于判断错误演进。不能指定用户、提交或题目。")
    public List<AiSubmissionHistoryDTO> getRecentAttempts(
            @ToolParam(description = "需要获取的历史提交数量，只能为1到3") int limit,
            ToolContext toolContext) {
        Object currentCalled = toolContext.getContext().get(CURRENT_TOOL_CALLED);
        if (!(currentCalled instanceof AtomicBoolean marker) || !marker.get()) {
            throw new IllegalStateException("必须先获取当前提交上下文");
        }
        Long userId = requiredLong(toolContext, USER_ID);
        Long submissionId = requiredLong(toolContext, SUBMISSION_ID);
        incrementToolCount(toolContext);
        int safeLimit = Math.max(1, Math.min(3, limit));
        BaseResponse<List<AiSubmissionHistoryDTO>> response = questionServiceClient.getSubmissionHistory(
                submissionId, userId, safeLimit, internalToken);
        if (response == null || response.getCode() != 0) {
            throw new IllegalStateException("历史提交上下文获取失败");
        }
        List<AiSubmissionHistoryDTO> history = response.getData();
        if (history == null) {
            return Collections.emptyList();
        }
        history.forEach(item -> item.setCode(truncate(item.getCode(), MAX_HISTORY_CODE_LENGTH)));
        return history;
    }

    private AiSubmissionContextDTO sanitizeContext(AiSubmissionContextDTO source, int codeLimit) {
        AiSubmissionContextDTO target = new AiSubmissionContextDTO();
        target.setSubmissionId(source.getSubmissionId());
        target.setQuestionId(source.getQuestionId());
        target.setTitle(source.getTitle());
        target.setContent(source.getContent());
        target.setTags(source.getTags());
        target.setDifficulty(source.getDifficulty());
        target.setCode(truncate(source.getCode(), codeLimit));
        target.setLanguage(source.getLanguage());
        target.setJudgeInfo(source.getJudgeInfo());
        target.setStatus(source.getStatus());
        target.setLastError(source.getLastError());
        target.setCreateTime(source.getCreateTime());
        return target;
    }

    private Long requiredLong(ToolContext toolContext, String key) {
        Object value = toolContext.getContext().get(key);
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("工具可信上下文缺少 " + key);
    }

    private void incrementToolCount(ToolContext toolContext) {
        Object counter = toolContext.getContext().get(TOOL_CALL_COUNT);
        if (counter instanceof AtomicInteger toolCalls) {
            toolCalls.incrementAndGet();
        }
    }

    private String truncate(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "\n/* 内容过长，已由服务端截断 */";
    }
}

package com.qwerlty.myojbackendaiservice.authoring.graph;

import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;

public class AuthoringState extends AgentState {

    public static final String TASK_ID = "taskId";
    public static final String REQUEST_JSON = "requestJson";
    public static final String DRAFT_JSON = "draftJson";
    public static final String ERRORS_JSON = "errorsJson";
    public static final String REPAIR_COUNT = "repairCount";
    public static final String SANDBOX_PASSED = "sandboxPassed";
    public static final String MODEL_NAME = "modelName";
    public static final String PROMPT_VERSION = "promptVersion";
    public static final String RESULT_STATUS = "resultStatus";
    public static final String REVIEW_DECISION = "reviewDecision";
    public static final String REVIEWER_ID = "reviewerId";
    public static final String REVIEWED_DRAFT_JSON = "reviewedDraftJson";
    public static final String PUBLISHED_QUESTION_ID = "publishedQuestionId";

    public AuthoringState(Map<String, Object> initData) {
        super(initData);
    }

    public long taskId() {
        Object value = data().get(TASK_ID);
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    public String string(String key) {
        Object value = data().get(key);
        return value == null ? "" : value.toString();
    }

    public int integer(String key) {
        Object value = data().get(key);
        if (value instanceof Number number) return number.intValue();
        if (value == null) return 0;
        return Integer.parseInt(value.toString());
    }

    public boolean bool(String key) {
        Object value = data().get(key);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    public long longValue(String key) {
        Object value = data().get(key);
        if (value instanceof Number number) return number.longValue();
        if (value == null) return 0;
        return Long.parseLong(value.toString());
    }
}

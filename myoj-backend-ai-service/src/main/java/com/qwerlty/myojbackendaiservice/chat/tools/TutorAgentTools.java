package com.qwerlty.myojbackendaiservice.chat.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.chat.agent.ChatEventSink;
import com.qwerlty.myojbackendaiservice.chat.model.AiToolEvent;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TutorAgentTools {

    private final TutorToolService toolService;
    private final ObjectMapper objectMapper;
    private final TutorToolContext context;
    private final int maxObservationChars;
    private final ChatEventSink sink;
    private final List<AiToolEvent> events = new ArrayList<>();
    private final StringBuilder observations = new StringBuilder();

    public TutorAgentTools(TutorToolService toolService,
                           ObjectMapper objectMapper,
                           TutorToolContext context,
                           int maxObservationChars,
                           ChatEventSink sink) {
        this.toolService = toolService;
        this.objectMapper = objectMapper;
        this.context = context;
        this.maxObservationChars = maxObservationChars;
        this.sink = sink;
    }

    @Tool(name = "searchWeb", description = "Search public algorithm-problem information relevant to the user's question")
    public String searchWeb(@ToolParam(description = "Search keyword") String query) {
        return execute("searchWeb", json(Map.of("query", query == null ? "" : query)));
    }

    @Tool(name = "submission_analysis", description = "Analyze the user's recent submissions for the current problem")
    public String submissionAnalysis(
            @ToolParam(required = false, description = "Maximum number of recent submissions, default 10") Integer limit) {
        return execute("submission_analysis", json(Map.of("limit", limit == null ? 10 : limit)));
    }

    @Tool(name = "testcase_generator", description = "Generate boundary, tricky, and stress-test suggestions for the current problem")
    public String generateTestCases() {
        return execute("testcase_generator", "{}");
    }

    @Tool(name = "sample_error_analyzer", description = "Analyze a compile, runtime, wrong-answer, timeout, or memory-limit error")
    public String analyzeSampleError(
            @ToolParam(required = false, description = "Error text; omit to use the latest judge result") String errorText) {
        return execute("sample_error_analyzer",
                json(Map.of("errorText", errorText == null ? "" : errorText)));
    }

    @Tool(name = "run_user_code", description = "Run the user-provided code against the test inputs from the current request")
    public String runUserCode() {
        return execute("run_user_code", "{}");
    }

    public List<AiToolEvent> events() {
        return List.copyOf(events);
    }

    public String observations() {
        return observations.toString();
    }

    private String execute(String toolName, String input) {
        TutorToolResult result = toolService.execute(toolName, input, context);
        events.add(result.event());
        if (sink != null) {
            sink.emit("tool", result.event());
        }
        observations.append("\n工具 ").append(toolName).append("：\n")
                .append(truncate(result.output(), maxObservationChars));
        return result.output();
    }

    private String json(Map<String, Object> input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("工具参数序列化失败", exception);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

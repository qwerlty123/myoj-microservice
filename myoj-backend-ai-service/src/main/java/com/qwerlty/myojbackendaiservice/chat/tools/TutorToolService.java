package com.qwerlty.myojbackendaiservice.chat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.chat.client.QuestionContextClient;
import com.qwerlty.myojbackendaiservice.chat.model.AiToolEvent;
import com.qwerlty.myojbackendaiservice.chat.model.QuestionContext;
import com.qwerlty.myojbackendaiservice.chat.model.SubmissionContext;
import com.qwerlty.myojbackendaiservice.chat.model.SubmissionQuery;
import com.qwerlty.myojbackendaiservice.chat.repository.AiChatRepository;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecuteResponse;
import com.qwerlty.myojbackendaiservice.sandbox.SandboxExecutionProfile;
import com.qwerlty.myojbackendaiservice.sandbox.SignedCodeSandboxClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class TutorToolService {

    public static final List<String> TOOL_NAMES = List.of(
            "searchWeb", "submission_analysis", "testcase_generator", "sample_error_analyzer", "run_user_code");

    private final AiChatRepository repository;
    private final QuestionContextClient questionClient;
    private final SignedCodeSandboxClient sandboxClient;
    private final AiAgentProperties.Search searchProperties;
    private final ObjectMapper objectMapper;
    private final RestClient searchClient;

    public TutorToolService(AiChatRepository repository,
                            QuestionContextClient questionClient,
                            SignedCodeSandboxClient sandboxClient,
                            AiAgentProperties properties,
                            ObjectMapper objectMapper) {
        this.repository = repository;
        this.questionClient = questionClient;
        this.sandboxClient = sandboxClient;
        this.searchProperties = properties.getSearch();
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(searchProperties.getTimeout());
        factory.setReadTimeout(searchProperties.getTimeout());
        this.searchClient = RestClient.builder().requestFactory(factory).build();
    }

    public TutorToolResult execute(String toolName, String rawInput, TutorToolContext context) {
        if (!TOOL_NAMES.contains(toolName)) {
            return failed(toolName, "未知工具：" + toolName, context);
        }
        AiChatRepository.ToolPolicy policy = repository.findToolPolicy(toolName);
        if (!policy.enabled()) {
            return failed(toolName, "该工具当前已禁用", context);
        }
        if (policy.dailyLimit() > 0
                && repository.countToolCallsToday(context.userId(), toolName) >= policy.dailyLimit()) {
            return failed(toolName, "今日调用次数已达上限", context);
        }
        try {
            JsonNode input = parseInput(rawInput);
            String output = switch (toolName) {
                case "searchWeb" -> searchWeb(text(input, "query", context.request().message()));
                case "submission_analysis" -> submissionAnalysis(input, context);
                case "testcase_generator" -> generateTestCases(context.question());
                case "sample_error_analyzer" -> analyzeSampleError(
                        text(input, "errorText", context.request().latestJudgeResult()), context.question());
                case "run_user_code" -> runUserCode(context);
                default -> throw new IllegalArgumentException("未知工具：" + toolName);
            };
            repository.saveToolCall(context.userId(), context.sessionId(), toolName, true, output);
            return new TutorToolResult(new AiToolEvent(toolName, "done", truncate(output, 300)), output);
        } catch (Exception exception) {
            return failed(toolName, concise(exception.getMessage()), context);
        }
    }

    private TutorToolResult failed(String toolName, String message, TutorToolContext context) {
        repository.saveToolCall(context.userId(), context.sessionId(), toolName, false, message);
        return new TutorToolResult(new AiToolEvent(toolName, "error", message), "工具调用失败：" + message);
    }

    private String searchWeb(String query) throws Exception {
        if (!StringUtils.hasText(query)) {
            return "搜索关键词为空。";
        }
        if (!StringUtils.hasText(searchProperties.getApiKey())) {
            return "搜索工具未配置 BAIDU_AI_SEARCH_API_KEY。";
        }
        Map<String, Object> body = Map.of(
                "messages", List.of(Map.of("content", query, "role", "user")),
                "search_filter", Map.of("match", Map.of("site", List.of("leetcode.cn"))));
        JsonNode response = searchClient.post()
                .uri(searchProperties.getApiUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + searchProperties.getApiKey())
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(JsonNode.class);
        if (response == null || !response.path("references").isArray()) {
            return "没有搜索到可用资料。";
        }
        List<String> results = new ArrayList<>();
        for (JsonNode item : response.path("references")) {
            if (results.size() >= searchProperties.getMaxResults()) {
                break;
            }
            results.add("标题：" + item.path("title").asText()
                    + "\n摘要：" + truncate(item.path("content").asText(), 500)
                    + "\n链接：" + item.path("url").asText());
        }
        return results.isEmpty() ? "没有搜索到可用资料。" : String.join("\n\n", results);
    }

    private String submissionAnalysis(JsonNode input, TutorToolContext context) {
        int limit = Math.max(1, Math.min(input.path("limit").asInt(10), 20));
        List<SubmissionContext> submissions = questionClient.listSubmissions(
                new SubmissionQuery(context.userId(), context.question().id(), limit));
        if (submissions == null || submissions.isEmpty()) {
            return "没有找到这道题的提交记录。";
        }
        int accepted = 0;
        String latestMessage = "N/A";
        Long latestTime = null;
        for (int index = 0; index < submissions.size(); index++) {
            SubmissionContext submission = submissions.get(index);
            JsonNode judgeInfo = readTree(submission.judgeInfo());
            String message = judgeInfo.path("message").asText("");
            if ("Accepted".equalsIgnoreCase(message) || "成功".equals(message)) {
                accepted++;
            }
            if (index == 0) {
                latestMessage = StringUtils.hasText(message) ? message
                        : StringUtils.hasText(submission.lastError()) ? submission.lastError() : "N/A";
                if (judgeInfo.hasNonNull("time")) {
                    latestTime = judgeInfo.path("time").asLong();
                }
            }
        }
        return "共 " + submissions.size() + " 次提交：[通过 " + accepted + " 次，最近结果："
                + latestMessage + "，执行时间：" + (latestTime == null ? "N/A" : latestTime) + " ms]。";
    }

    private String generateTestCases(QuestionContext question) {
        String title = blankToEmpty(question.title());
        String topic = (title + "\n" + blankToEmpty(question.content()) + "\n" + blankToEmpty(question.tags()))
                .toLowerCase(Locale.ROOT);
        Set<String> boundary = new LinkedHashSet<>();
        Set<String> tricky = new LinkedHashSet<>();
        Set<String> stress = new LinkedHashSet<>();
        boundary.add("最小合法输入（允许时覆盖 0、1），检查初始化和下标。");
        boundary.add("最大合法值域，检查整数溢出和边界比较。");
        if (containsAny(topic, "array", "数组", "双指针", "滑动窗口")) {
            boundary.add("空数组、单元素、全相等、严格递增和严格递减数组。");
            tricky.add("大量重复值，检查指针移动是否漏算或重复计数。");
        }
        if (containsAny(topic, "string", "字符串", "子串")) {
            boundary.add("空串、单字符、全相同字符和交替字符。");
            tricky.add("重复模式和首尾命中，检查子串边界。");
        }
        if (containsAny(topic, "binary", "二分", "有序")) {
            boundary.add("目标在首尾、目标不存在和重复元素场景。");
            tricky.add("双元素区间，检查 mid 更新是否会死循环。");
        }
        if (containsAny(topic, "dp", "动态规划")) {
            boundary.add("只包含基础状态和第一次状态转移的输入。");
            tricky.add("不可达状态与不同遍历顺序，检查状态复用。");
        }
        if (containsAny(topic, "graph", "图", "tree", "树", "bfs", "dfs")) {
            boundary.add("单节点、非连通分量以及允许时的环和自环。");
            tricky.add("多连通分量，检查 visited 的初始化和重置。");
        }
        if (StringUtils.hasText(question.judgeCase())) {
            boundary.add("逐条回放官方样例，核对输入解析与输出格式。");
        }
        stress.add("接近约束上限的数据，验证时间和空间复杂度。");
        stress.add("全相等、严格单调、频次高度偏斜的对抗数据。");
        return section("题目测试建议：" + (title.isBlank() ? "未命名题目" : title), "边界场景", boundary)
                + section("", "易错正确性场景", tricky)
                + section("", "压力场景", stress);
    }

    private String analyzeSampleError(String errorText, QuestionContext question) {
        if (!StringUtils.hasText(errorText)) {
            return "当前没有明确报错信息。请提供 latestJudgeResult 或粘贴编译器/运行时日志。";
        }
        String lower = errorText.toLowerCase(Locale.ROOT);
        String category;
        List<String> hints;
        if (containsAny(lower, "compile", "syntax", "编译", "cannot find symbol")) {
            category = "编译错误";
            hints = List.of("检查变量/方法拼写、导包和 Java 版本。", "从第一条编译错误开始修复，后续错误可能是连锁反应。");
        } else if (containsAny(lower, "runtime", "exception", "nullpointer", "越界", "空指针")) {
            category = "运行时错误";
            hints = List.of("根据堆栈定位首个业务代码行。", "检查空值、数组下标、除零和递归深度。");
        } else if (containsAny(lower, "wrong answer", "答案错误", "expected", "输出不一致", " wa")) {
            category = "答案错误";
            hints = List.of("核对边界条件、初始化和输出格式。", "用最小反例逐步比较期望值与实际值。");
        } else if (containsAny(lower, "time limit", "tle", "超时")) {
            category = "超时";
            hints = List.of("结合数据范围重新估算复杂度。", "排查重复计算、低效容器操作和死循环。");
        } else if (containsAny(lower, "memory limit", "mle", "内存")) {
            category = "内存超限";
            hints = List.of("检查大数组维度和对象数量。", "考虑滚动数组或流式处理。");
        } else {
            category = "未知类型错误";
            hints = List.of("补充完整报错、输入和实际输出。", "先回放官方样例并打印关键中间状态。");
        }
        return "报错类型判断：" + category + "\n判题/报错信息：" + truncate(errorText, 1500)
                + "\n可能原因：\n- " + String.join("\n- ", hints)
                + (StringUtils.hasText(question.judgeCase()) ? "\n样例对照：题目包含官方样例，请逐条回放。" : "");
    }

    private String runUserCode(TutorToolContext context) throws Exception {
        String code = context.request().userCode();
        String language = context.request().language();
        if (!StringUtils.hasText(code) || !StringUtils.hasText(language)
                || context.request().testInputs() == null || context.request().testInputs().isEmpty()) {
            throw new IllegalArgumentException("请求未提供可执行代码、语言或测试输入");
        }
        List<String> inputs = new ArrayList<>(context.request().testInputs());
        SandboxExecuteResponse response = sandboxClient.execute(
                code, language, inputs, SandboxExecutionProfile.aiTutor());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", response.status());
        summary.put("message", response.message());
        summary.put("outputList", response.outputList());
        summary.put("judgeInfo", response.judgeInfo());
        summary.put("caseResults", response.caseResults());
        return objectMapper.writeValueAsString(summary);
    }

    private JsonNode parseInput(String rawInput) {
        if (!StringUtils.hasText(rawInput)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(rawInput);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode().put("query", rawInput).put("errorText", rawInput);
        }
    }

    private JsonNode readTree(String value) {
        try {
            return StringUtils.hasText(value) ? objectMapper.readTree(value) : objectMapper.createObjectNode();
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private static String text(JsonNode input, String field, String fallback) {
        String value = input.path(field).asText(null);
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static boolean containsAny(String source, String... values) {
        for (String value : values) {
            if (source.contains(value.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String section(String prefix, String name, Set<String> items) {
        StringBuilder value = new StringBuilder();
        if (!prefix.isBlank()) {
            value.append(prefix).append('\n');
        }
        value.append(name).append("：\n");
        items.forEach(item -> value.append("- ").append(item).append('\n'));
        return value.toString();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String concise(String message) {
        return StringUtils.hasText(message) ? truncate(message, 300) : "未知错误";
    }
}

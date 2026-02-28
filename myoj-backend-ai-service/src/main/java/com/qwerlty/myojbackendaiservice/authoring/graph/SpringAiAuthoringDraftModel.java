package com.qwerlty.myojbackendaiservice.authoring.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.authoring.api.ProblemDraftRequirements;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringProblemDraft;
import com.qwerlty.myojbackendaiservice.chat.repository.AiChatRepository;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.observability.AiMetrics;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class SpringAiAuthoringDraftModel implements AuthoringDraftModel {

    private static final String DEFAULT_PROMPT = """
            你是 MyOJ 的算法题出题助手。根据需求生成一题原创、可独立评测的 ACM 输入输出题。
            输出必须满足结构化类型约束，并遵守以下规则：
            1. 仅提供 Java 17 的 public class Main 参考代码，必须从标准输入读取并写入标准输出。
            2. 题面 content 使用 Markdown，包含题目描述、输入格式、输出格式、数据范围和样例，但不得泄露解法。
            3. answer 使用 Markdown，包含思路、复杂度和完整 Java 参考代码。
            4. 必须生成 6 至 8 组互不重复的测试用例，覆盖常规与边界情况，每组 input/output 都不能为空。
            5. judgeConfig 使用毫秒和 KB，默认时间 1000、内存 262144、栈 262144。
            不要复制公开题目的成段表述，不要输出 JSON 之外的解释。
            """;

    private final ChatClient chatClient;
    private final AiChatRepository repository;
    private final AiAgentProperties.Authoring properties;
    private final ObjectMapper objectMapper;
    private final AiMetrics metrics;
    private final String configuredModel;

    public SpringAiAuthoringDraftModel(ChatModel chatModel,
                                       AiChatRepository repository,
                                       AiAgentProperties properties,
                                       ObjectMapper objectMapper,
                                       AiMetrics metrics,
                                       @Value("${spring.ai.openai.chat.options.model:}") String configuredModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.repository = repository;
        this.properties = properties.getAuthoring();
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.configuredModel = configuredModel;
    }

    @Override
    public GenerationOutcome generate(ProblemDraftRequirements requirements) {
        return call("generate", requirements, null, List.of());
    }

    @Override
    public GenerationOutcome repair(ProblemDraftRequirements requirements,
                                    AuthoringProblemDraft draft,
                                    List<String> validationErrors) {
        return call("repair", requirements, draft, validationErrors);
    }

    private GenerationOutcome call(String action,
                                   ProblemDraftRequirements requirements,
                                   AuthoringProblemDraft draft,
                                   List<String> validationErrors) {
        AiChatRepository.PromptDefinition prompt = repository.findActivePromptDefinition("authoring")
                .orElse(new AiChatRepository.PromptDefinition(properties.getPromptVersion(), DEFAULT_PROMPT));
        String modelName = repository.findActiveModelName().filter(StringUtils::hasText)
                .orElse(configuredModel);
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().temperature(0.2);
        if (StringUtils.hasText(modelName)) options.model(modelName);
        String userMessage = action.equals("repair")
                ? repairMessage(requirements, draft, validationErrors)
                : generateMessage(requirements);
        long startedAt = System.currentTimeMillis();
        try {
            ResponseEntity<ChatResponse, AuthoringProblemDraft> response = chatClient.prompt()
                    .system(prompt.content())
                    .user(userMessage)
                    .options(options.build())
                    .call()
                    .responseEntity(AuthoringProblemDraft.class);
            AuthoringProblemDraft result = response.entity();
            if (result == null) {
                throw new IllegalStateException("模型没有返回结构化题目草稿");
            }
            metrics.recordModelCall("authoring_" + action, "success",
                    System.currentTimeMillis() - startedAt);
            return new GenerationOutcome(
                    result,
                    modelName,
                    prompt.version(),
                    promptTokens(response.response()),
                    completionTokens(response.response())
            );
        } catch (RuntimeException exception) {
            metrics.recordModelCall("authoring_" + action, "error",
                    System.currentTimeMillis() - startedAt);
            throw exception;
        }
    }

    private String generateMessage(ProblemDraftRequirements requirements) {
        return """
                出题需求：%s
                难度：%s（0 简单、1 中等、2 困难）
                标签：%s
                知识点：%s
                额外约束：%s
                """.formatted(requirements.topic(), requirements.difficulty(), requirements.tags(),
                requirements.knowledgePoints(), blank(requirements.constraints()));
    }

    private String repairMessage(ProblemDraftRequirements requirements,
                                 AuthoringProblemDraft draft,
                                 List<String> validationErrors) {
        try {
            return """
                    请修复下面的题目草稿，并完整返回修复后的结构化对象。
                    原始需求：%s
                    验证错误：%s
                    当前草稿：%s
                    不得减少到 6 组以下测试用例，不得绕过沙箱错误。
                    """.formatted(generateMessage(requirements), validationErrors,
                    truncate(objectMapper.writeValueAsString(draft), 20_000));
        } catch (Exception exception) {
            throw new IllegalStateException("无法构造题目修复请求", exception);
        }
    }

    private static String blank(String value) {
        return StringUtils.hasText(value) ? value : "无";
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static Integer promptTokens(ChatResponse response) {
        if (response == null || response.getMetadata() == null
                || response.getMetadata().getUsage() == null) return null;
        Integer value = response.getMetadata().getUsage().getPromptTokens();
        return value != null && value > 0 ? value : null;
    }

    private static Integer completionTokens(ChatResponse response) {
        if (response == null || response.getMetadata() == null
                || response.getMetadata().getUsage() == null) return null;
        Integer value = response.getMetadata().getUsage().getCompletionTokens();
        return value != null && value > 0 ? value : null;
    }
}

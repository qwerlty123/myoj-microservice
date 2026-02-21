package com.qwerlty.myojbackendaiservice.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.agent.GenerationSession;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.enums.MessageType;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

public class WebSearchTools {

    private final AiAgentProperties.Search properties;
    private final ObjectMapper objectMapper;
    private final GenerationSession session;
    private final RestClient restClient;

    public WebSearchTools(AiAgentProperties.Search properties,
                          ObjectMapper objectMapper,
                          GenerationSession session) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.session = session;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getTimeout());
        requestFactory.setReadTimeout(properties.getTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Tool(name = "webSearch", description = "Search algorithm-problem information from Baidu AI Search, limited to leetcode.cn")
    public String webSearch(@ToolParam(description = "Search keyword") String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return "搜索失败：关键词为空";
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            return "搜索工具未配置 BAIDU_AI_SEARCH_API_KEY，请根据已有知识继续";
        }
        session.emit(MessageType.TOOL, "正在搜索相关算法题资料：" + truncate(keyword, 80));
        try {
            Map<String, Object> body = Map.of(
                    "messages", List.of(Map.of("content", keyword, "role", "user")),
                    "search_filter", Map.of("match", Map.of("site", List.of("leetcode.cn")))
            );
            JsonNode response = restClient.post()
                    .uri(properties.getApiUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.path("references").isArray()) {
                return "没有搜索到可用资料";
            }
            StringBuilder result = new StringBuilder();
            int count = 0;
            for (JsonNode item : response.path("references")) {
                if (count++ >= properties.getMaxResults()) {
                    break;
                }
                result.append("title: ").append(item.path("title").asText()).append('\n')
                        .append("content: ").append(truncate(item.path("content").asText(), 500)).append('\n')
                        .append("url: ").append(item.path("url").asText()).append("\n\n");
            }
            return result.isEmpty() ? "没有搜索到可用资料" : result.toString();
        } catch (Exception exception) {
            return "搜索失败：" + truncate(exception.getMessage(), 300);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

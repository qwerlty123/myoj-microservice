package com.qwerlty.myojbackendaiservice.tools;

import com.qwerlty.myojbackendaiservice.agent.GenerationSession;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.enums.MessageType;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.net.URI;
import java.util.Locale;

public class HtmlCrawlerTools {

    private final AiAgentProperties.Crawler properties;
    private final GenerationSession session;

    public HtmlCrawlerTools(AiAgentProperties.Crawler properties, GenerationSession session) {
        this.properties = properties;
        this.session = session;
    }

    @Tool(name = "crawler", description = "Read the visible text of an allowed algorithm web page")
    public String crawlHtml(@ToolParam(description = "HTTPS URL returned by webSearch") String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !isAllowedHost(uri.getHost())) {
                return "抓取失败：只允许访问配置中的 HTTPS 算法站点";
            }
            session.emit(MessageType.TOOL, "正在读取网页：" + url);
            Document document = Jsoup.connect(uri.toString())
                    .userAgent("MyOJ question authoring agent/1.0")
                    .timeout(Math.toIntExact(properties.getTimeout().toMillis()))
                    .maxBodySize(properties.getMaxBodyBytes())
                    .get();
            document.select("script,style,noscript,svg,link").remove();
            String text = document.title() + "\n" + document.body().text();
            return text.length() <= properties.getMaxTextLength()
                    ? text
                    : text.substring(0, properties.getMaxTextLength());
        } catch (Exception exception) {
            String message = exception.getMessage();
            return "抓取失败：" + (message == null ? "无效网址" : truncate(message, 300));
        }
    }

    private boolean isAllowedHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return properties.getAllowedHosts().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> normalized.equals(value) || normalized.endsWith("." + value));
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

package com.qwerlty.myojbackendaiservice.chat.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

@Component
public class ChatSafetyPolicy {

    private static final List<String> INJECTION_PATTERNS = List.of(
            "ignoreprevious", "ignoreallprevious", "developer message", "system prompt",
            "revealprompt", "忽略之前", "忽略以上", "系统提示词", "开发者消息");

    public boolean containsPromptInjection(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        String compact = normalized.replaceAll("[\\s_\\-，。,:：;；]+", "");
        return INJECTION_PATTERNS.stream().anyMatch(pattern ->
                normalized.contains(pattern) || compact.contains(pattern.replace(" ", "")));
    }

    public String matchedSensitiveWord(String text, List<String> words) {
        if (!StringUtils.hasText(text) || words == null) {
            return null;
        }
        return words.stream().filter(StringUtils::hasText).filter(text::contains).findFirst().orElse(null);
    }
}

package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedJudgeCase;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemSourceDraft;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class DraftFingerprint {
    private DraftFingerprint() { }

    public static String source(ProblemSourceDraft draft, ObjectMapper objectMapper) {
        JudgeConfigValue config = draft.getJudgeConfig();
        List<Object> canonical = new ArrayList<>();
        canonical.add(normalize(draft.getTitle()));
        canonical.add(normalize(draft.getContent()));
        canonical.add(draft.getDifficulty());
        canonical.add(draft.getTags() == null ? List.of() : draft.getTags());
        canonical.add(normalize(draft.getAnswer()));
        canonical.add((draft.getJudgeCase() == null ? List.<GeneratedJudgeCase>of() : draft.getJudgeCase())
                .stream().map(item -> List.of(normalize(item.getInput()), normalize(item.getOutput()))).toList());
        canonical.add(config == null ? List.of() : List.of(
                config.getTimeLimit(), config.getMemoryLimit(), config.getStackLimit()));
        return hashJson(canonical, objectMapper);
    }

    public static String value(Object value, ObjectMapper objectMapper) {
        return hashJson(value, objectMapper);
    }

    private static String hashJson(Object value, ObjectMapper objectMapper) {
        try {
            byte[] json = objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("题目快照无法序列化", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算题目快照摘要", exception);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n");
    }
}

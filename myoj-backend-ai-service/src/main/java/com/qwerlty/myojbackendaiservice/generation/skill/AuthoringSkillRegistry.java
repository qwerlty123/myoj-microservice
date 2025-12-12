package com.qwerlty.myojbackendaiservice.generation.skill;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/** Loads trusted, versioned authoring skills and exposes only phase-relevant guidance. */
@Component
public class AuthoringSkillRegistry {
    private static final String SKILL_PATTERN = "classpath*:authoring-skills/*/SKILL.md";
    private static final int MAX_GUIDANCE_CHARS = 7_000;
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9]+", Pattern.CASE_INSENSITIVE);

    private final int maxSelected;
    private final List<AuthoringSkill> skills;
    private final String catalogFingerprint;

    public AuthoringSkillRegistry(
            @Value("${myoj.ai.generation.skills.max-selected:3}") int maxSelected) {
        this.maxSelected = Math.max(1, Math.min(5, maxSelected));
        this.skills = loadSkills();
        this.catalogFingerprint = fingerprint(skills);
    }

    public AuthoringSkillSelection select(AuthoringSkillContext context) {
        String haystack = context.searchText().toLowerCase(Locale.ROOT);
        List<SkillMatch> matches = skills.stream()
                .filter(skill -> skill.sections().containsKey(context.phase()))
                .map(skill -> new SkillMatch(skill, matchScore(skill, haystack)))
                .filter(match -> match.skill().always() || match.score() > 0)
                .sorted(Comparator
                        .comparing((SkillMatch match) -> match.skill().always()).reversed()
                        .thenComparing(SkillMatch::score, Comparator.reverseOrder())
                        .thenComparing(match -> match.skill().priority(), Comparator.reverseOrder())
                        .thenComparing(match -> match.skill().name()))
                .limit(maxSelected)
                .toList();

        List<String> identifiers = matches.stream()
                .map(match -> match.skill().name() + "@" + match.skill().version())
                .toList();
        StringBuilder guidance = new StringBuilder("<authoring-skills catalog=\"")
                .append(catalogFingerprint).append("\">\n");
        for (SkillMatch match : matches) {
            String identifier = match.skill().name() + "@" + match.skill().version();
            String section = match.skill().sections().get(context.phase());
            String block = "[" + identifier + "]\n" + section.strip() + "\n";
            if (guidance.length() + block.length() + 21 > MAX_GUIDANCE_CHARS) break;
            guidance.append(block);
        }
        guidance.append("</authoring-skills>");
        return new AuthoringSkillSelection(identifiers, guidance.toString());
    }

    public String catalogFingerprint() {
        return catalogFingerprint;
    }

    private List<AuthoringSkill> loadSkills() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(SKILL_PATTERN);
            if (resources.length == 0) throw new IllegalStateException("未找到出题 Skill: " + SKILL_PATTERN);
            List<Resource> ordered = new ArrayList<>(List.of(resources));
            ordered.sort(Comparator.comparing(Resource::getDescription));
            Map<String, AuthoringSkill> loaded = new LinkedHashMap<>();
            for (Resource resource : ordered) {
                AuthoringSkill skill = parseSkill(resource);
                if (loaded.putIfAbsent(skill.name(), skill) != null) {
                    throw new IllegalStateException("出题 Skill 名称重复: " + skill.name());
                }
            }
            return List.copyOf(loaded.values());
        } catch (Exception exception) {
            throw new IllegalStateException("加载出题 Skill 失败", exception);
        }
    }

    private AuthoringSkill parseSkill(Resource resource) throws Exception {
        String raw = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        Map<String, String> frontmatter = frontmatter(raw);
        String name = required(frontmatter, "name", resource);
        required(frontmatter, "description", resource);

        Resource metadataResource = resource.createRelative("authoring.properties");
        if (!metadataResource.exists()) {
            throw new IllegalStateException(name + " 缺少 authoring.properties");
        }
        Properties metadata = new Properties();
        try (InputStreamReader reader = new InputStreamReader(metadataResource.getInputStream(), StandardCharsets.UTF_8)) {
            metadata.load(reader);
        }
        String version = required(metadata, "version", name);
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalStateException(name + " version 必须使用 x.y.z 格式");
        }
        int priority = Integer.parseInt(metadata.getProperty("priority", "0"));
        boolean always = Boolean.parseBoolean(metadata.getProperty("always", "false"));
        List<String> keywords = split(metadata.getProperty("keywords", ""));
        if (!always && keywords.isEmpty()) throw new IllegalStateException(name + " 缺少匹配 keywords");
        Map<AuthoringSkillPhase, String> sections = sections(body(raw), name);
        if (sections.isEmpty()) throw new IllegalStateException(name + " 未定义任何阶段规则");
        return new AuthoringSkill(name, version, priority, always, keywords, sections);
    }

    private Map<String, String> frontmatter(String raw) {
        if (!raw.startsWith("---")) throw new IllegalStateException("SKILL.md 缺少 YAML frontmatter");
        int end = raw.indexOf("\n---", 3);
        if (end < 0) throw new IllegalStateException("SKILL.md frontmatter 未闭合");
        Map<String, String> result = new HashMap<>();
        for (String line : raw.substring(3, end).split("\\R")) {
            int separator = line.indexOf(':');
            if (separator > 0) {
                result.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        }
        return result;
    }

    private String body(String raw) {
        int end = raw.indexOf("\n---", 3);
        return raw.substring(end + 4).strip();
    }

    private Map<AuthoringSkillPhase, String> sections(String body, String skillName) {
        Map<AuthoringSkillPhase, String> result = new LinkedHashMap<>();
        AuthoringSkillPhase current = null;
        StringBuilder content = new StringBuilder();
        for (String line : body.split("\\R", -1)) {
            if (line.startsWith("## ")) {
                if (current != null) result.put(current, content.toString().strip());
                String heading = line.substring(3).trim();
                try {
                    current = AuthoringSkillPhase.valueOf(heading);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalStateException(skillName + " 包含未知阶段: " + heading, exception);
                }
                content = new StringBuilder();
            } else if (current != null) {
                content.append(line).append('\n');
            }
        }
        if (current != null) result.put(current, content.toString().strip());
        result.entrySet().removeIf(entry -> entry.getValue().isBlank());
        return Map.copyOf(result);
    }

    private int matchScore(AuthoringSkill skill, String haystack) {
        int score = 0;
        for (String keyword : skill.keywords()) {
            if (containsKeyword(haystack, keyword)) score++;
        }
        return score;
    }

    private boolean containsKeyword(String haystack, String keyword) {
        if (!ASCII_WORD.matcher(keyword).matches() || keyword.length() > 3) return haystack.contains(keyword);
        return Pattern.compile("(^|[^a-z0-9])" + Pattern.quote(keyword) + "([^a-z0-9]|$)")
                .matcher(haystack).find();
    }

    private List<String> split(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Pattern.compile(",").splitAsStream(raw)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private String required(Map<String, String> values, String key, Resource resource) throws Exception {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(resource.getURL() + " 缺少 " + key);
        return value;
    }

    private String required(Properties values, String key, String skillName) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(skillName + " 缺少 " + key);
        return value.trim();
    }

    private String fingerprint(List<AuthoringSkill> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            values.stream().sorted(Comparator.comparing(AuthoringSkill::name)).forEach(skill -> {
                digest.update(skill.name().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(skill.version().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Integer.toString(skill.priority()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) (skill.always() ? 1 : 0));
                skill.keywords().forEach(keyword -> digest.update(keyword.getBytes(StandardCharsets.UTF_8)));
                skill.sections().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    digest.update(entry.getKey().name().getBytes(StandardCharsets.UTF_8));
                    digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
                });
            });
            return HexFormat.of().formatHex(digest.digest()).substring(0, 12);
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算 Skill 目录指纹", exception);
        }
    }

    private record AuthoringSkill(String name,
                                  String version,
                                  int priority,
                                  boolean always,
                                  List<String> keywords,
                                  Map<AuthoringSkillPhase, String> sections) {
    }

    private record SkillMatch(AuthoringSkill skill, int score) {
    }
}

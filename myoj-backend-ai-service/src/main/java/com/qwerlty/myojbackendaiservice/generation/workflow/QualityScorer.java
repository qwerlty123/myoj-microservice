package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityDimension;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityIssue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QualityScorer {
    private static final Map<String, Integer> WEIGHTS = new LinkedHashMap<>();
    static {
        WEIGHTS.put("COMPLETENESS", 15);
        WEIGHTS.put("CONSISTENCY", 15);
        WEIGHTS.put("SOLUTION", 25);
        WEIGHTS.put("TEST_CASES", 35);
        WEIGHTS.put("JUDGE_CONFIG", 10);
    }

    private QualityScorer() { }

    public static ScoreResult score(List<QualityIssue> issues, boolean complete) {
        List<QualityDimension> dimensions = new ArrayList<>();
        int total = 0;
        boolean blocker = false;
        for (Map.Entry<String, Integer> entry : WEIGHTS.entrySet()) {
            String dimension = entry.getKey();
            if (!complete && !"COMPLETENESS".equals(dimension)) {
                dimensions.add(new QualityDimension(dimension, entry.getValue(), "UNCHECKED", null));
                continue;
            }
            int score = 100;
            for (QualityIssue issue : issues) {
                if (!dimension.equals(normalize(issue.getDimension()))) continue;
                int penalty = switch (normalize(issue.getSeverity())) {
                    case "BLOCKER" -> 100;
                    case "MAJOR" -> 30;
                    case "MINOR" -> 10;
                    default -> 0;
                };
                score = Math.max(0, score - penalty);
                blocker |= "BLOCKER".equals(normalize(issue.getSeverity()));
            }
            dimensions.add(new QualityDimension(dimension, entry.getValue(), "CHECKED", score));
            total += Math.round(score * entry.getValue() / 100.0f);
        }
        Integer totalScore = complete ? (blocker ? Math.min(59, total) : total) : null;
        return new ScoreResult(dimensions, totalScore);
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(); }

    public record ScoreResult(List<QualityDimension> dimensions, Integer totalScore) { }
}

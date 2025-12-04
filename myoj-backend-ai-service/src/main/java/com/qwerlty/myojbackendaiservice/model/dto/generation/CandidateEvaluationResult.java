package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data @NoArgsConstructor @AllArgsConstructor
public class CandidateEvaluationResult {
    private int round;
    private int submitted;
    private int accepted;
    private int rejected;
    private int totalAccepted;
    private int remaining;
    private Map<String, Integer> categoryCounts;
    private List<String> missingCategories = new ArrayList<>();
    private List<String> uncoveredRiskIds = new ArrayList<>();
    private List<CandidateRejection> rejections = new ArrayList<>();
}

package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.Data;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class CoverageReport {
    private int targetCount;
    private int acceptedCount;
    private int rejectedCount;
    private Map<String, Integer> categoryCounts = new LinkedHashMap<>();
    private List<CoverageRisk> dynamicRisks = new ArrayList<>();
    private List<String> uncoveredRiskIds = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
}

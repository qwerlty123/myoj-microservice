package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class GenerationValidationReport {
    private List<String> compiledLanguages = new ArrayList<>();
    private Boolean crossLanguageMatched;
    private Boolean validatorPassed;
    private Boolean oracleMatched;
    private Integer totalCases;
    private Integer oracleCases;
    private Integer duplicateCases;
    private Integer qualityScore;
    private Map<String, Integer> categoryCounts = new LinkedHashMap<>();
    private List<String> warnings = new ArrayList<>();
}

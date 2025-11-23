package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class GeneratedProblemSpec {
    private String title;
    private String content;
    private Integer difficulty;
    private List<String> tags = new ArrayList<>();
    private String solutionExplanation;
    private JudgeConfigValue judgeConfig;
    private List<GeneratedTestInput> sampleInputs = new ArrayList<>();
}

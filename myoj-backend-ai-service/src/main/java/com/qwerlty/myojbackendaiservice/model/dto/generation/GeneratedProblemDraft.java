package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class GeneratedProblemDraft {
    private String title;
    private String content;
    private Integer difficulty;
    private List<String> tags = new ArrayList<>();
    private String answer;
    private List<ReferenceSolution> referenceSolutions = new ArrayList<>();
    private List<GeneratedJudgeCase> judgeCase = new ArrayList<>();
    private JudgeConfigValue judgeConfig;
}

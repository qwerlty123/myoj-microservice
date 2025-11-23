package com.qwerlty.myojbackendaiservice.model.dto.generation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ProblemSourceDraft {
    private String clientQuestionId;

    @Size(max = 80)
    private String title;

    @Size(max = 8192)
    private String content;

    @Min(0)
    @Max(2)
    private Integer difficulty;

    @Size(max = 10)
    private List<@Size(max = 32) String> tags = new ArrayList<>();

    @Size(max = 200000)
    private String answer;

    @Valid
    @Size(max = 50)
    private List<GeneratedJudgeCase> judgeCase = new ArrayList<>();

    @Valid
    private JudgeConfigValue judgeConfig;
}

package com.qwerlty.myojbackendaiservice.model.dto.generation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ProblemSourceDraft {
    private String clientQuestionId;

    @NotBlank
    @Size(max = 80)
    private String title;

    @NotBlank
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
    private JudgeConfigValue judgeConfig;
}

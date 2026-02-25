package com.qwerlty.myojbackendaiservice.authoring.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProblemDraftRequirements(
        @NotBlank(message = "题目主题不能为空")
        @Size(max = 500, message = "题目主题不能超过 500 个字符")
        String topic,
        @Min(value = 0, message = "难度只能是 0、1 或 2")
        @Max(value = 2, message = "难度只能是 0、1 或 2")
        Integer difficulty,
        @Size(max = 10, message = "标签不能超过 10 个")
        List<@Size(max = 32, message = "单个标签不能超过 32 个字符") String> tags,
        @Size(max = 10, message = "知识点不能超过 10 个")
        List<@Size(max = 64, message = "单个知识点不能超过 64 个字符") String> knowledgePoints,
        @Size(max = 4000, message = "额外约束不能超过 4000 个字符")
        String constraints
) {
    public ProblemDraftRequirements {
        difficulty = difficulty == null ? 1 : difficulty;
        tags = tags == null ? List.of() : List.copyOf(tags);
        knowledgePoints = knowledgePoints == null ? List.of() : List.copyOf(knowledgePoints);
    }
}

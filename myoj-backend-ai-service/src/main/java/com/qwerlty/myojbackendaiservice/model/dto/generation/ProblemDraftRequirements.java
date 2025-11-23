package com.qwerlty.myojbackendaiservice.model.dto.generation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ProblemDraftRequirements {
    @Size(min = 1, max = 500)
    private String topic;
    @Min(0) @Max(2) @NotNull
    private Integer difficulty = 1;
    @Size(max = 10)
    private List<@Size(max = 32) String> tags = new ArrayList<>();
    @Size(max = 10)
    private List<@Size(max = 64) String> knowledgePoints = new ArrayList<>();
    @Size(max = 4000)
    private String constraints;
}

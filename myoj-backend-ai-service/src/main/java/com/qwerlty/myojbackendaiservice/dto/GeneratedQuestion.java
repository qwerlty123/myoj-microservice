package com.qwerlty.myojbackendaiservice.dto;

import java.util.List;

public record GeneratedQuestion(String description, String answer, List<JudgeCase> judgeCaseList) {

    public GeneratedQuestion {
        judgeCaseList = judgeCaseList == null ? List.of() : List.copyOf(judgeCaseList);
    }
}

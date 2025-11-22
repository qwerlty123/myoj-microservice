package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedJudgeCase {
    private String input;
    private String output;
    private String category;
}

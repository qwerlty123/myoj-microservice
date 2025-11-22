package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.Data;

@Data
public class GeneratedTestInput {
    private String category;
    private String input;
    private Boolean oracleEligible = true;
}

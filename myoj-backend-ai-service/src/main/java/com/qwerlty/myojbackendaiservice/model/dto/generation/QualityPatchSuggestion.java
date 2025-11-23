package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.Data;

@Data
public class QualityPatchSuggestion {
    private String operation;
    private String target;
    private Object afterValue;
    private String reason;
}

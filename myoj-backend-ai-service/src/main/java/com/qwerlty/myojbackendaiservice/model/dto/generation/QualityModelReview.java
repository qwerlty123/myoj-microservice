package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class QualityModelReview {
    private List<QualityIssue> issues = new ArrayList<>();
    private List<QualityPatchSuggestion> patchSuggestions = new ArrayList<>();
    private Boolean answerNeedsReplacement = false;
    private String canonicalExplanation;
}

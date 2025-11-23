package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class QualityVerificationSummary {
    private boolean semanticReviewed;
    private boolean checkSolutionsCompiled;
    private boolean crossLanguageMatched;
    private int totalCases;
    private int verifiedCases;
    private int oracleCases;
    private List<String> skippedChecks = new ArrayList<>();
}

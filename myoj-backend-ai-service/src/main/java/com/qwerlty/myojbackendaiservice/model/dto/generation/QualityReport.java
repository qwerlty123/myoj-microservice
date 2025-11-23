package com.qwerlty.myojbackendaiservice.model.dto.generation;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class QualityReport {
    private boolean complete;
    private Integer totalScore;
    private List<QualityDimension> dimensions = new ArrayList<>();
    private List<QualityIssue> issues = new ArrayList<>();
    private QualityVerificationSummary verification = new QualityVerificationSummary();
}

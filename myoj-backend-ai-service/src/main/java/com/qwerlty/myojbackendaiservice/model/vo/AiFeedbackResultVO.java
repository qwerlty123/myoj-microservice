package com.qwerlty.myojbackendaiservice.model.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiFeedbackResultVO {
    private String verdict;
    private String summary;
    private String errorCategory;
    private String rootCause;
    private List<SuspiciousCodeVO> suspiciousCode = new ArrayList<>();
    private List<String> debuggingSteps = new ArrayList<>();
    private ComplexityVO complexity;
    private List<HintVO> hints = new ArrayList<>();
    private List<String> improvements = new ArrayList<>();
    private List<CitationVO> citations = new ArrayList<>();
}

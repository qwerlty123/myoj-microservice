package com.qwerlty.myojbackendaiservice.model.dto;

import com.qwerlty.myojbackendaiservice.model.vo.AiFeedbackResultVO;
import com.qwerlty.myojbackendaiservice.model.vo.CitationVO;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AiAnalysisResult {
    private AiFeedbackResultVO result;
    private List<CitationVO> citations;
    private int inputTokens;
    private int outputTokens;
}

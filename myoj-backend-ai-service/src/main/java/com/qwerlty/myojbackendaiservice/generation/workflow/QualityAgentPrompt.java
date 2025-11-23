package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.model.dto.generation.CaseEvidence;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ProblemSourceDraft;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityIssue;

import java.util.List;

public record QualityAgentPrompt(
        ProblemSourceDraft sourceDraft,
        List<QualityIssue> deterministicIssues,
        List<CaseEvidence> baselineEvidence) {
}

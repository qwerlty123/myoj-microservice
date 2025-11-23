package com.qwerlty.myojbackendaiservice.generation;

import com.qwerlty.myojbackendaiservice.generation.workflow.QualityAgentPrompt;
import com.qwerlty.myojbackendaiservice.generation.workflow.QualityEvidenceTools;
import com.qwerlty.myojbackendaiservice.generation.workflow.TestCaseAgentPrompt;
import com.qwerlty.myojbackendaiservice.generation.workflow.TestCaseAgentTools;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityModelReview;

public interface AuthoringAgentModel {
    void generateTestCases(TestCaseAgentPrompt prompt, TestCaseAgentTools tools);

    QualityModelReview reviewQuality(QualityAgentPrompt prompt, QualityEvidenceTools tools);
}

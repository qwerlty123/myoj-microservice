package com.qwerlty.myojbackendaiservice.generation;

import com.qwerlty.myojbackendaiservice.generation.workflow.QualityAgentPrompt;
import com.qwerlty.myojbackendaiservice.generation.workflow.QualityEvidenceTools;
import com.qwerlty.myojbackendaiservice.generation.workflow.DraftRepairPrompt;
import com.qwerlty.myojbackendaiservice.generation.workflow.ProblemDraftTools;
import com.qwerlty.myojbackendaiservice.generation.workflow.TestCaseAgentPrompt;
import com.qwerlty.myojbackendaiservice.generation.workflow.TestCaseAgentTools;
import com.qwerlty.myojbackendaiservice.generation.knowledge.AuthoringKnowledgeTool;
import com.qwerlty.myojbackendaiservice.model.dto.generation.QualityModelReview;

public interface AuthoringAgentModel {
    default void repairProblemDraft(DraftRepairPrompt prompt, ProblemDraftTools tools) {
        throw new UnsupportedOperationException("当前模型不支持题目草稿修复");
    }

    void generateTestCases(TestCaseAgentPrompt prompt, TestCaseAgentTools tools);

    default void generateTestCases(TestCaseAgentPrompt prompt,
                                   TestCaseAgentTools tools,
                                   AuthoringKnowledgeTool knowledgeTool) {
        generateTestCases(prompt, tools);
    }

    QualityModelReview reviewQuality(QualityAgentPrompt prompt, QualityEvidenceTools tools);

    default QualityModelReview reviewQuality(QualityAgentPrompt prompt,
                                             QualityEvidenceTools tools,
                                             AuthoringKnowledgeTool knowledgeTool) {
        return reviewQuality(prompt, tools);
    }
}

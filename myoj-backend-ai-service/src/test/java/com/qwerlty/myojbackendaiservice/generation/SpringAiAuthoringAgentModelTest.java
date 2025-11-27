package com.qwerlty.myojbackendaiservice.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.config.SpringAiConfig;
import com.qwerlty.myojbackendaiservice.generation.workflow.BatchVerificationResult;
import com.qwerlty.myojbackendaiservice.generation.workflow.SandboxBatchVerifier;
import com.qwerlty.myojbackendaiservice.generation.workflow.TestCaseAgentPrompt;
import com.qwerlty.myojbackendaiservice.generation.workflow.TestCaseAgentTools;
import com.qwerlty.myojbackendaiservice.generation.workflow.TestCaseGenerationState;
import com.qwerlty.myojbackendaiservice.generation.workflow.WorkflowContext;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateRejection;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CoveragePlan;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiAuthoringAgentModelTest {

    @Test
    void toolCallAdvisorExecutesModelToolResultModelLoop() {
        ScriptedToolCallingModel chatModel = new ScriptedToolCallingModel();
        SpringAiAuthoringAgentModel agent = new SpringAiAuthoringAgentModel(
                new SpringAiConfig().authoringAgentChatClient(chatModel), new ObjectMapper());
        SandboxBatchVerifier verifier = mock(SandboxBatchVerifier.class);
        when(verifier.verify(anyList(), anyList(), any(ValidationPrograms.class), any()))
                .thenReturn(new BatchVerificationResult(List.of(),
                        List.of(new CandidateRejection("validator", "输入格式不合法")), 0));
        TestCaseGenerationState state = new TestCaseGenerationState();
        state.setSpecification(new GeneratedProblemSpec());
        state.setCoveragePlan(new CoveragePlan());
        state.setPrograms(new ValidationPrograms());
        TestCaseAgentTools tools = new TestCaseAgentTools(
                WorkflowContext.testing(99L), verifier, state, 10);

        agent.generateTestCases(new TestCaseAgentPrompt(
                state.getSpecification(), state.getCoveragePlan(), 10, "边界"), tools);

        assertThat(chatModel.prompts).hasSize(2);
        assertThat(chatModel.prompts.get(1).getInstructions())
                .anyMatch(ToolResponseMessage.class::isInstance);
        assertThat(state.getRounds()).isEqualTo(1);
        verify(verifier).verify(anyList(), anyList(), any(ValidationPrograms.class), any());
    }

    private static final class ScriptedToolCallingModel implements ChatModel {
        private final List<Prompt> prompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            if (prompts.size() == 1) {
                AssistantMessage toolCall = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "evaluateCandidateCases", """
                                {"candidates":[{"input":"1\\n","category":"NORMAL","riskIds":[],"oracleEligible":true}]}
                                """)))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCall)));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("已根据拒绝原因结束本轮"))));
        }
    }
}

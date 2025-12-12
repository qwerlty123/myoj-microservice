package com.qwerlty.myojbackendaiservice.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.config.SpringAiConfig;
import com.qwerlty.myojbackendaiservice.generation.sandbox.AuthoringSandboxVerifier;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationOutcome;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationReport;
import com.qwerlty.myojbackendaiservice.generation.sandbox.VerificationRequest;
import com.qwerlty.myojbackendaiservice.generation.workflow.TestCaseAgentPrompt;
import com.qwerlty.myojbackendaiservice.generation.workflow.TestCaseAgentTools;
import com.qwerlty.myojbackendaiservice.generation.workflow.TestCaseGenerationState;
import com.qwerlty.myojbackendaiservice.generation.workflow.DraftRepairPrompt;
import com.qwerlty.myojbackendaiservice.generation.workflow.ProblemDraftTools;
import com.qwerlty.myojbackendaiservice.generation.workflow.ProblemDraftWorkflow;
import com.qwerlty.myojbackendaiservice.generation.workflow.WorkflowContext;
import com.qwerlty.myojbackendaiservice.generation.skill.AuthoringSkillRegistry;
import com.qwerlty.myojbackendaiservice.generation.knowledge.AuthoringKnowledgeRetriever;
import com.qwerlty.myojbackendaiservice.generation.knowledge.AuthoringKnowledgeTool;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CandidateRejection;
import com.qwerlty.myojbackendaiservice.model.dto.generation.CoveragePlan;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedTestInput;
import com.qwerlty.myojbackendaiservice.model.dto.generation.JudgeConfigValue;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ReferenceSolution;
import com.qwerlty.myojbackendaiservice.model.dto.generation.ValidationPrograms;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiAuthoringAgentModelTest {

    @Test
    void draftRepairExecutesModelToolResultModelLoop() {
        ObjectMapper objectMapper = new ObjectMapper();
        ProblemDraftWorkflow.DraftState state = draftState();
        AuthoringSandboxVerifier verifier = mock(AuthoringSandboxVerifier.class);
        VerificationReport passed = new VerificationReport(
                VerificationOutcome.PASSED, List.of(), List.of(), 0, List.of());
        when(verifier.verify(any(VerificationRequest.class))).thenReturn(passed);
        ProblemDraftTools tools = new ProblemDraftTools(
                WorkflowContext.testing(88L), verifier, state, objectMapper);
        DraftToolCallingModel chatModel = new DraftToolCallingModel(tools.currentStateHash());
        AiModelGateway gateway = mock(AiModelGateway.class);
        when(gateway.callWithUsage(any(), any(), any())).thenAnswer(invocation ->
                ((AiModelGateway.ModelCallResult<?>) ((Supplier<?>) invocation.getArgument(2)).get()).value());
        SpringAiAuthoringAgentModel agent = new SpringAiAuthoringAgentModel(
                new SpringAiConfig().authoringAgentChatClient(chatModel), objectMapper, gateway,
                new AuthoringSkillRegistry(3));

        agent.repairProblemDraft(new DraftRepairPrompt(state.getSpecification(), state.getSolutions(),
                state.getPrograms(), new VerificationReport(VerificationOutcome.REPAIRABLE,
                List.of(), List.of(), 0, List.of()), tools.currentStateHash(), tools.remainingCalls()), tools);

        assertThat(chatModel.prompts).hasSize(2);
        assertThat(chatModel.prompts.get(1).getInstructions())
                .anyMatch(ToolResponseMessage.class::isInstance);
        assertThat(state.getSolutions()).filteredOn(solution -> "cpp".equals(solution.getLanguage()))
                .extracting(ReferenceSolution::getCode).containsExactly("cpp-fixed");
    }

    @Test
    void toolCallAdvisorExecutesModelToolResultModelLoop() {
        ScriptedToolCallingModel chatModel = new ScriptedToolCallingModel();
        AiModelGateway gateway = mock(AiModelGateway.class);
        when(gateway.callWithUsage(any(), any(), any())).thenAnswer(invocation ->
                ((AiModelGateway.ModelCallResult<?>) ((Supplier<?>) invocation.getArgument(2)).get()).value());
        SpringAiAuthoringAgentModel agent = new SpringAiAuthoringAgentModel(
                new SpringAiConfig().authoringAgentChatClient(chatModel), new ObjectMapper(), gateway,
                new AuthoringSkillRegistry(3));
        AuthoringSandboxVerifier verifier = mock(AuthoringSandboxVerifier.class);
        when(verifier.verify(any(VerificationRequest.class)))
                .thenReturn(new VerificationReport(VerificationOutcome.REPAIRABLE, List.of(),
                        List.of(new CandidateRejection("validator", "输入格式不合法")), 0, List.of()));
        TestCaseGenerationState state = new TestCaseGenerationState();
        state.setSpecification(new GeneratedProblemSpec());
        state.getSpecification().setTags(List.of("array"));
        state.setCoveragePlan(new CoveragePlan());
        state.setPrograms(new ValidationPrograms());
        WorkflowContext context = WorkflowContext.testing(99L, AuthoringTaskType.TEST_CASES);
        TestCaseAgentTools tools = new TestCaseAgentTools(context, verifier, state, 10);
        VectorStore knowledgeStore = new VectorStore() {
            @Override public void add(List<Document> documents) { }
            @Override public void delete(List<String> ids) { }
            @Override public void delete(Filter.Expression filterExpression) { }
            @Override public List<Document> similaritySearch(SearchRequest request) {
                return List.of(new Document("boundary", "sequence boundary guidance",
                        Map.of("docId", "boundary", "title", "序列边界")));
            }
        };
        AuthoringKnowledgeTool knowledgeTool = new AuthoringKnowledgeTool(context,
                new AuthoringKnowledgeRetriever(knowledgeStore, 3, 0.68, 1200));

        agent.generateTestCases(new TestCaseAgentPrompt(
                state.getSpecification(), state.getCoveragePlan(), 10, "边界"), tools, knowledgeTool);

        assertThat(chatModel.prompts).hasSize(3);
        assertThat(chatModel.prompts.get(0).getContents())
                .contains("大规模输入绝不能", "RANGE", "32 KiB",
                        "missingCategories 为空", "不能只生成 NORMAL",
                        "enforce-problem-contract@1.0.0", "cover-sequence-boundaries@1.0.0");
        assertThat(chatModel.prompts.get(1).getInstructions())
                .anyMatch(ToolResponseMessage.class::isInstance);
        assertThat(chatModel.prompts.get(2).getInstructions())
                .anyMatch(ToolResponseMessage.class::isInstance);
        assertThat(state.getRounds()).isEqualTo(1);
        assertThat(context.toolTrace()).extracting("toolName")
                .containsExactly("searchAuthoringKnowledge", "evaluateCandidateCases");
        verify(verifier).verify(any(VerificationRequest.class));
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
                                "call-knowledge", "function", "searchAuthoringKnowledge", """
                                {"query":"数组边界风险"}
                                """)))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCall)));
            }
            if (prompts.size() == 2) {
                AssistantMessage toolCall = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "evaluateCandidateCases", """
                                {"candidates":[{"chunks":[{"type":"LITERAL","value":"1\\n"}],"category":"NORMAL","riskIds":[],"oracleEligible":true}]}
                                """)))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCall)));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("已根据拒绝原因结束本轮"))));
        }
    }

    private static final class DraftToolCallingModel implements ChatModel {
        private final List<Prompt> prompts = new ArrayList<>();
        private final String stateHash;

        private DraftToolCallingModel(String stateHash) {
            this.stateHash = stateHash;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            if (prompts.size() == 1) {
                AssistantMessage toolCall = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "draft-call", "function", "verifyDraftPatch", """
                                {"patch":{"baseHash":"%s","operations":[{"target":"/solutions/cpp","afterValue":"cpp-fixed","reason":"compile error"}]}}
                                """.formatted(stateHash))))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCall)));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("修复完成"))));
        }
    }

    private ProblemDraftWorkflow.DraftState draftState() {
        ProblemDraftWorkflow.DraftState state = new ProblemDraftWorkflow.DraftState();
        GeneratedProblemSpec spec = new GeneratedProblemSpec();
        spec.setTitle("Draft");
        spec.setContent("## 题目描述\n描述");
        spec.setSolutionExplanation("题解");
        spec.setJudgeConfig(new JudgeConfigValue());
        for (String input : List.of("1", "2")) {
            GeneratedTestInput sample = new GeneratedTestInput();
            sample.setInput(input);
            spec.getSampleInputs().add(sample);
        }
        state.setSpecification(spec);
        state.setSolutions(List.of(solution("java"), solution("cpp"), solution("go")));
        ValidationPrograms programs = new ValidationPrograms();
        programs.setValidatorJava("validator");
        programs.setOracleJava("oracle");
        state.setPrograms(programs);
        return state;
    }

    private ReferenceSolution solution(String language) {
        ReferenceSolution solution = new ReferenceSolution();
        solution.setLanguage(language);
        solution.setCode(language + "-code");
        return solution;
    }
}

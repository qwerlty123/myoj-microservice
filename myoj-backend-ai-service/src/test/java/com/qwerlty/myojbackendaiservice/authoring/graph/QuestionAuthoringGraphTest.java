package com.qwerlty.myojbackendaiservice.authoring.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.authoring.api.ProblemDraftRequirements;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringProblemDraft;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringStage;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringTask;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringTaskStatus;
import com.qwerlty.myojbackendaiservice.authoring.repository.AuthoringTaskRepository;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.dto.JudgeCase;
import com.qwerlty.myojbackendaiservice.observability.AiMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestionAuthoringGraphTest {

    private AuthoringTaskRepository repository;
    private AuthoringSandboxVerifier sandboxVerifier;
    private ObjectMapper objectMapper;
    private AiAgentProperties properties;

    @BeforeEach
    void setUp() {
        repository = mock(AuthoringTaskRepository.class);
        sandboxVerifier = mock(AuthoringSandboxVerifier.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        properties = new AiAgentProperties();
        when(repository.isCancelRequested(anyLong())).thenReturn(false);
    }

    @Test
    void completesWhenFirstDraftPassesValidationAndSandbox() throws Exception {
        AuthoringProblemDraft draft = validDraft(6);
        QuestionAuthoringGraph graph = graph(model(draft, draft));
        when(sandboxVerifier.verify(draft)).thenReturn(passed());

        graph.execute(task(101));

        verify(repository).complete(anyLong(), anyString(), anyInt());
        verify(repository, never()).fail(anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    void repairsOnceAndThenCompletes() throws Exception {
        AuthoringProblemDraft invalid = validDraft(5);
        AuthoringProblemDraft repaired = validDraft(6);
        QuestionAuthoringGraph graph = graph(model(invalid, repaired));
        when(sandboxVerifier.verify(repaired)).thenReturn(passed());

        graph.execute(task(102));

        verify(repository).complete(anyLong(), anyString(), org.mockito.ArgumentMatchers.eq(1));
    }

    @Test
    void completesWhenThirdRepairFinallyPasses() throws Exception {
        AuthoringProblemDraft invalid = validDraft(5);
        AuthoringProblemDraft valid = validDraft(6);
        AuthoringDraftModel draftModel = mock(AuthoringDraftModel.class);
        when(draftModel.generate(org.mockito.ArgumentMatchers.any())).thenReturn(outcome(invalid));
        when(draftModel.repair(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(outcome(invalid), outcome(invalid), outcome(valid));
        when(sandboxVerifier.verify(valid)).thenReturn(passed());

        graph(draftModel).execute(task(107));

        verify(draftModel, times(3)).repair(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(repository).complete(anyLong(), anyString(), org.mockito.ArgumentMatchers.eq(3));
    }

    @Test
    void failsAfterThreeRepairsRemainInvalid() throws Exception {
        AuthoringProblemDraft invalid = validDraft(5);
        AuthoringDraftModel draftModel = model(invalid, invalid);
        QuestionAuthoringGraph graph = graph(draftModel);

        graph.execute(task(103));

        verify(repository).fail(anyLong(), org.mockito.ArgumentMatchers.eq("VALIDATION_FAILED"),
                anyString(), org.mockito.ArgumentMatchers.eq(3));
        verify(draftModel, times(3)).repair(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(repository, never()).complete(anyLong(), anyString(), anyInt());
    }

    @Test
    void surfacesModelFailureForTaskServiceToPersist() throws Exception {
        AuthoringDraftModel failingModel = mock(AuthoringDraftModel.class);
        when(failingModel.generate(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("model unavailable"));
        QuestionAuthoringGraph graph = graph(failingModel);

        assertThatThrownBy(() -> graph.execute(task(104)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model unavailable");
    }

    @Test
    void sandboxTimeoutUsesThreeRepairsAndThenFails() throws Exception {
        AuthoringProblemDraft draft = validDraft(6);
        QuestionAuthoringGraph graph = graph(model(draft, draft));
        when(sandboxVerifier.verify(draft)).thenReturn(new AuthoringSandboxVerifier.SandboxVerification(
                false, List.of("代码沙箱调用异常：Read timed out")));

        graph.execute(task(105));

        verify(sandboxVerifier, times(4)).verify(draft);
        verify(repository).fail(anyLong(), org.mockito.ArgumentMatchers.eq("VALIDATION_FAILED"),
                anyString(), org.mockito.ArgumentMatchers.eq(3));
    }

    @Test
    void cancellationStopsBeforeCallingModel() throws Exception {
        AuthoringDraftModel model = mock(AuthoringDraftModel.class);
        QuestionAuthoringGraph graph = graph(model);
        when(repository.isCancelRequested(106L)).thenReturn(true);

        assertThatThrownBy(() -> graph.execute(task(106)))
                .isInstanceOf(AuthoringCancelledException.class);
        verify(model, never()).generate(org.mockito.ArgumentMatchers.any());
    }

    private QuestionAuthoringGraph graph(AuthoringDraftModel model) throws Exception {
        return new QuestionAuthoringGraph(repository, model, new AuthoringDraftValidator(), sandboxVerifier,
                properties, objectMapper, new AiMetrics(new SimpleMeterRegistry()), new MemorySaver());
    }

    private AuthoringDraftModel model(AuthoringProblemDraft generated, AuthoringProblemDraft repaired) {
        AuthoringDraftModel model = mock(AuthoringDraftModel.class);
        when(model.generate(org.mockito.ArgumentMatchers.any())).thenReturn(outcome(generated));
        when(model.repair(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(outcome(repaired));
        return model;
    }

    private static AuthoringDraftModel.GenerationOutcome outcome(AuthoringProblemDraft draft) {
        return new AuthoringDraftModel.GenerationOutcome(draft, "test-model", "test-v1");
    }

    private AuthoringTask task(long id) throws Exception {
        String request = objectMapper.writeValueAsString(new ProblemDraftRequirements(
                "数组求和", 0, List.of("数组"), List.of("模拟"), null));
        LocalDateTime now = LocalDateTime.now();
        return new AuthoringTask(id, 7L, null, "idem-" + id, "PROBLEM_DRAFT", request, null,
                AuthoringTaskStatus.RUNNING, AuthoringStage.QUEUED, 1, 0, false,
                null, null, null, "test-v1", "authoring-v1", now, null, now, now);
    }

    private static AuthoringProblemDraft validDraft(int caseCount) {
        List<JudgeCase> cases = new ArrayList<>();
        for (int index = 1; index <= caseCount; index++) {
            cases.add(new JudgeCase(index + "\n", index + "\n"));
        }
        String text = "这是一道用于测试结构化出题工作流的数组算法题。".repeat(8);
        return new AuthoringProblemDraft(
                "数组求和",
                0,
                text,
                List.of("数组"),
                text,
                "public class Main { public static void main(String[] args) { } }",
                cases,
                AuthoringProblemDraft.JudgeConfig.defaults()
        );
    }

    private static AuthoringSandboxVerifier.SandboxVerification passed() {
        return new AuthoringSandboxVerifier.SandboxVerification(true, List.of());
    }
}

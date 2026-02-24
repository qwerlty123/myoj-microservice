package com.qwerlty.myojbackendaiservice.authoring.graph;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.authoring.api.ProblemDraftRequirements;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringProblemDraft;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringStage;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringTask;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringValidation;
import com.qwerlty.myojbackendaiservice.authoring.model.ProblemDraftArtifact;
import com.qwerlty.myojbackendaiservice.authoring.repository.AuthoringTaskRepository;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.observability.AiMetrics;
import io.micrometer.core.instrument.Timer;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Component
public class QuestionAuthoringGraph {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final AuthoringTaskRepository repository;
    private final AuthoringDraftModel draftModel;
    private final AuthoringDraftValidator validator;
    private final AuthoringSandboxVerifier sandboxVerifier;
    private final AiAgentProperties.Authoring properties;
    private final ObjectMapper objectMapper;
    private final AiMetrics metrics;
    private final CompiledGraph<AuthoringState> workflow;

    public QuestionAuthoringGraph(AuthoringTaskRepository repository,
                                  AuthoringDraftModel draftModel,
                                  AuthoringDraftValidator validator,
                                  AuthoringSandboxVerifier sandboxVerifier,
                                  AiAgentProperties properties,
                                  ObjectMapper objectMapper,
                                  AiMetrics metrics,
                                  BaseCheckpointSaver checkpointSaver) throws GraphStateException {
        this.repository = repository;
        this.draftModel = draftModel;
        this.validator = validator;
        this.sandboxVerifier = sandboxVerifier;
        this.properties = properties.getAuthoring();
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.workflow = build(checkpointSaver);
    }

    public void execute(AuthoringTask task) {
        RunnableConfig config = config(task.id());
        try {
            Map<String, Object> input = workflow.stateOf(config).isPresent() ? null : initialState(task);
            workflow.invoke(input, config).orElseThrow();
        } catch (Exception exception) {
            Throwable cause = rootCause(exception);
            if (cause instanceof AuthoringCancelledException cancelled) throw cancelled;
            throw new IllegalStateException("AI 出题工作流执行失败：" + concise(cause.getMessage()), cause);
        }
    }

    public boolean hasCheckpoint(long taskId) {
        return workflow.stateOf(config(taskId)).isPresent();
    }

    private CompiledGraph<AuthoringState> build(BaseCheckpointSaver saver) throws GraphStateException {
        StateGraph<AuthoringState> graph = new StateGraph<>(AuthoringState::new);
        graph.addNode(Node.GENERATE.id, node_async(state -> runNode(Node.GENERATE, state, () -> generate(state))));
        graph.addNode(Node.VALIDATE.id, node_async(state -> runNode(Node.VALIDATE, state, () -> validate(state))));
        graph.addNode(Node.VERIFY.id, node_async(state -> runNode(Node.VERIFY, state, () -> verify(state))));
        graph.addNode(Node.REPAIR.id, node_async(state -> runNode(Node.REPAIR, state, () -> repair(state))));
        graph.addNode(Node.FINALIZE.id, node_async(state -> runNode(Node.FINALIZE, state, () -> finalizeDraft(state))));
        graph.addNode(Node.FAIL.id, node_async(state -> runNode(Node.FAIL, state, () -> fail(state))));
        graph.addEdge(START, Node.GENERATE.id);
        graph.addEdge(Node.GENERATE.id, Node.VALIDATE.id);
        graph.addConditionalEdges(Node.VALIDATE.id, edge_async(this::routeAfterValidation), Map.of(
                Node.VERIFY.id, Node.VERIFY.id,
                Node.REPAIR.id, Node.REPAIR.id,
                Node.FAIL.id, Node.FAIL.id
        ));
        graph.addConditionalEdges(Node.VERIFY.id, edge_async(this::routeAfterVerification), Map.of(
                Node.FINALIZE.id, Node.FINALIZE.id,
                Node.REPAIR.id, Node.REPAIR.id,
                Node.FAIL.id, Node.FAIL.id
        ));
        graph.addEdge(Node.REPAIR.id, Node.VALIDATE.id);
        graph.addEdge(Node.FINALIZE.id, END);
        graph.addEdge(Node.FAIL.id, END);
        return graph.compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .graphId(properties.getGraphVersion())
                .recursionLimit(Math.max(16, 6 + (properties.getMaxRepairCount() + 1) * 3))
                .releaseThread(false)
                .build());
    }

    private Map<String, Object> generate(AuthoringState state) throws Exception {
        return draftUpdate(state.taskId(), draftModel.generate(readRequest(state)), repairCount(state));
    }

    private Map<String, Object> validate(AuthoringState state) throws Exception {
        return Map.of(
                AuthoringState.ERRORS_JSON, write(validator.validate(readDraft(state))),
                AuthoringState.SANDBOX_PASSED, false
        );
    }

    private Map<String, Object> verify(AuthoringState state) throws Exception {
        AuthoringSandboxVerifier.SandboxVerification result = sandboxVerifier.verify(readDraft(state));
        return Map.of(
                AuthoringState.SANDBOX_PASSED, result.passed(),
                AuthoringState.ERRORS_JSON, write(result.errors())
        );
    }

    private Map<String, Object> repair(AuthoringState state) throws Exception {
        int repairCount = repairCount(state) + 1;
        return draftUpdate(state.taskId(), draftModel.repair(
                readRequest(state), readDraft(state), readErrors(state)), repairCount);
    }

    private Map<String, Object> finalizeDraft(AuthoringState state) throws Exception {
        AuthoringProblemDraft draft = readDraft(state);
        ProblemDraftArtifact artifact = new ProblemDraftArtifact(
                draft, new AuthoringValidation("java", draft.judgeCase().size(), true, List.of()));
        repository.complete(state.taskId(), write(artifact), repairCount(state));
        metrics.task("review_required");
        return Map.of(AuthoringState.RESULT_STATUS, "REVIEW_REQUIRED");
    }

    private Map<String, Object> fail(AuthoringState state) throws Exception {
        String message = String.join("；", readErrors(state));
        repository.fail(state.taskId(), "VALIDATION_FAILED",
                StringUtils.hasText(message) ? message : "题目草稿未通过验证", repairCount(state));
        metrics.task("failed");
        return Map.of(AuthoringState.RESULT_STATUS, "FAILED");
    }

    private Map<String, Object> draftUpdate(long taskId,
                                             AuthoringDraftModel.GenerationOutcome outcome,
                                             int repairCount) throws Exception {
        repository.updateModel(taskId, outcome.modelName(), outcome.promptVersion());
        return Map.of(
                AuthoringState.DRAFT_JSON, write(outcome.draft()),
                AuthoringState.REPAIR_COUNT, repairCount,
                AuthoringState.MODEL_NAME, blank(outcome.modelName()),
                AuthoringState.PROMPT_VERSION, blank(outcome.promptVersion()),
                AuthoringState.ERRORS_JSON, "[]",
                AuthoringState.SANDBOX_PASSED, false
        );
    }

    private String routeAfterValidation(AuthoringState state) throws Exception {
        return route(readErrors(state).isEmpty(), Node.VERIFY, state);
    }

    private String routeAfterVerification(AuthoringState state) {
        return route(state.bool(AuthoringState.SANDBOX_PASSED), Node.FINALIZE, state);
    }

    private String route(boolean passed, Node success, AuthoringState state) {
        if (passed) return success.id;
        return repairCount(state) < properties.getMaxRepairCount() ? Node.REPAIR.id : Node.FAIL.id;
    }

    private Map<String, Object> runNode(Node node,
                                        AuthoringState state,
                                        CheckedSupplier action) throws Exception {
        Timer.Sample sample = metrics.start();
        int currentRepairs = repairCount(state);
        try {
            checkCancelled(state.taskId());
            int startedRepairs = node == Node.REPAIR ? currentRepairs + 1 : currentRepairs;
            repository.updateStage(state.taskId(), node.stage, progress(node, currentRepairs, false), startedRepairs);
            Map<String, Object> update = action.get();
            int completedRepairs = number(update.get(AuthoringState.REPAIR_COUNT), startedRepairs);
            repository.updateStage(state.taskId(), node.stage, progress(node, currentRepairs, true), completedRepairs);
            metrics.stopNode(sample, node.id, "success");
            return update;
        } catch (Exception exception) {
            metrics.stopNode(sample, node.id,
                    exception instanceof AuthoringCancelledException ? "cancelled" : "error");
            throw exception;
        }
    }

    private int progress(Node node, int repairCount, boolean completed) {
        if (node == Node.GENERATE) return completed ? 15 : 5;
        if (node == Node.FINALIZE || node == Node.FAIL) return completed ? 99 : 96;
        int attempts = Math.max(1, properties.getMaxRepairCount() + 1);
        double slot = 72.0 / attempts;
        double base = 20 + slot * Math.min(repairCount, attempts - 1);
        double fraction = switch (node) {
            case VALIDATE -> completed ? 0.25 : 0;
            case VERIFY -> completed ? 0.65 : 0.35;
            case REPAIR -> completed ? 0.95 : 0.75;
            default -> 0;
        };
        return Math.min(94, (int) Math.round(base + slot * fraction));
    }

    private void checkCancelled(long taskId) {
        if (Thread.currentThread().isInterrupted() || repository.isCancelRequested(taskId)) {
            throw new AuthoringCancelledException();
        }
    }

    private ProblemDraftRequirements readRequest(AuthoringState state) throws Exception {
        return objectMapper.readValue(state.string(AuthoringState.REQUEST_JSON), ProblemDraftRequirements.class);
    }

    private AuthoringProblemDraft readDraft(AuthoringState state) throws Exception {
        return objectMapper.readValue(state.string(AuthoringState.DRAFT_JSON), AuthoringProblemDraft.class);
    }

    private List<String> readErrors(AuthoringState state) throws Exception {
        String value = state.string(AuthoringState.ERRORS_JSON);
        return StringUtils.hasText(value) ? objectMapper.readValue(value, STRING_LIST) : List.of();
    }

    private String write(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private RunnableConfig config(long taskId) {
        return RunnableConfig.builder()
                .threadId("authoring-task-" + taskId)
                .graphId(properties.getGraphVersion())
                .build();
    }

    private static Map<String, Object> initialState(AuthoringTask task) {
        return Map.of(
                AuthoringState.TASK_ID, task.id(),
                AuthoringState.REQUEST_JSON, task.requestJson(),
                AuthoringState.REPAIR_COUNT, task.repairCount(),
                AuthoringState.SANDBOX_PASSED, false
        );
    }

    private static int repairCount(AuthoringState state) {
        return state.integer(AuthoringState.REPAIR_COUNT);
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static Throwable rootCause(Throwable value) {
        Throwable current = value;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private static String concise(String value) {
        if (!StringUtils.hasText(value)) return "未知错误";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private enum Node {
        GENERATE("generate_draft", AuthoringStage.GENERATING_DRAFT),
        VALIDATE("validate_draft", AuthoringStage.VALIDATING_DRAFT),
        VERIFY("sandbox_verify", AuthoringStage.VERIFYING_IN_SANDBOX),
        REPAIR("repair_draft", AuthoringStage.REPAIRING_DRAFT),
        FINALIZE("finalize", AuthoringStage.COMPLETED),
        FAIL("mark_failed", AuthoringStage.FAILED);

        private final String id;
        private final AuthoringStage stage;

        Node(String id, AuthoringStage stage) {
            this.id = id;
            this.stage = stage;
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier {
        Map<String, Object> get() throws Exception;
    }
}

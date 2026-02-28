package com.qwerlty.myojbackendaiservice.authoring.graph;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.authoring.api.ProblemDraftRequirements;
import com.qwerlty.myojbackendaiservice.authoring.client.AuthoringQuestionPublisher;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringProblemDraft;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringReviewDecision;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringStage;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringTask;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringValidation;
import com.qwerlty.myojbackendaiservice.authoring.model.ProblemDraftArtifact;
import com.qwerlty.myojbackendaiservice.authoring.repository.AuthoringTaskRepository;
import com.qwerlty.myojbackendaiservice.authoring.repository.AuthoringTraceRecorder;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.observability.AiMetrics;
import io.micrometer.core.instrument.Timer;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncCommandAction.command_async;
import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

@Component
public class QuestionAuthoringGraph {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final String RUN_ID_METADATA = "myoj.authoring.runId";

    private final AuthoringTaskRepository repository;
    private final AuthoringDraftModel draftModel;
    private final AuthoringDraftValidator validator;
    private final AuthoringSandboxVerifier sandboxVerifier;
    private final AuthoringQuestionPublisher questionPublisher;
    private final AiAgentProperties.Authoring properties;
    private final ObjectMapper objectMapper;
    private final AiMetrics metrics;
    private final AuthoringTraceRecorder trace;
    private final CompiledGraph<AuthoringState> workflow;

    public QuestionAuthoringGraph(AuthoringTaskRepository repository,
                                  AuthoringDraftModel draftModel,
                                  AuthoringDraftValidator validator,
                                  AuthoringSandboxVerifier sandboxVerifier,
                                  AuthoringQuestionPublisher questionPublisher,
                                  AiAgentProperties properties,
                                  ObjectMapper objectMapper,
                                  AiMetrics metrics,
                                  AuthoringTraceRecorder trace,
                                  BaseCheckpointSaver checkpointSaver) throws GraphStateException {
        this.repository = repository;
        this.draftModel = draftModel;
        this.validator = validator;
        this.sandboxVerifier = sandboxVerifier;
        this.questionPublisher = questionPublisher;
        this.properties = properties.getAuthoring();
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.trace = trace;
        this.workflow = build(checkpointSaver);
    }

    public void execute(AuthoringTask task) {
        String runId = newRunId();
        Instant runStarted = trace.started();
        RunnableConfig config = config(task.id(), runId);
        boolean recovery = workflow.stateOf(config).isPresent();
        trace.record(task.id(), task.graphVersion(), runId, "RUN_STARTED", null,
                null, null, "RUNNING", null, null,
                Map.of("mode", recovery ? "RECOVERY" : "INITIAL"));
        try {
            Map<String, Object> input = recovery ? null : initialState(task);
            AuthoringState result = workflow.invoke(input, config).orElseThrow();
            var snapshot = workflow.stateOf(config).orElse(null);
            String nextNode = snapshot == null ? null : snapshot.next();
            if (Node.HUMAN_REVIEW.id.equals(nextNode)) {
                trace.record(task.id(), task.graphVersion(), runId, "CHECKPOINT_INTERRUPTED",
                        Node.HUMAN_REVIEW.id, Node.PREPARE_REVIEW.id, Node.HUMAN_REVIEW.id,
                        "REVIEW_REQUIRED", null, null,
                        Map.of(
                                "checkpointBackend", "configured_saver",
                                "checkpointId", snapshot.config().checkPointId().orElse("")
                        ));
            }
            trace.record(task.id(), task.graphVersion(), runId, "RUN_FINISHED", null,
                    null, nextNode, result.string(AuthoringState.RESULT_STATUS),
                    trace.elapsedMs(runStarted), null,
                    Map.of("interrupted", Node.HUMAN_REVIEW.id.equals(nextNode)));
        } catch (Exception exception) {
            Throwable cause = rootCause(exception);
            trace.record(task.id(), task.graphVersion(), runId, "RUN_FAILED", null,
                    null, null, "ERROR", trace.elapsedMs(runStarted), null,
                    Map.of("errorType", cause.getClass().getSimpleName()));
            if (cause instanceof AuthoringCancelledException cancelled) throw cancelled;
            throw new IllegalStateException("AI 出题工作流执行失败：" + concise(cause.getMessage()), cause);
        }
    }

    public boolean hasCheckpoint(long taskId) {
        return workflow.stateOf(config(taskId)).isPresent();
    }

    public void resumeReview(AuthoringTask task) {
        if (!StringUtils.hasText(task.reviewDecision()) || !StringUtils.hasText(task.reviewDraftJson())
                || task.reviewerId() == null || task.reviewerId() <= 0) {
            throw new IllegalArgumentException("人工审核信息不完整，不能恢复发布");
        }
        String runId = newRunId();
        Instant runStarted = trace.started();
        RunnableConfig config = config(task.id(), runId);
        trace.record(task.id(), task.graphVersion(), runId, "RUN_STARTED", null,
                null, null, "RUNNING", null, task.reviewerId(), Map.of("mode", "HUMAN_REVIEW"));
        try {
            Map<String, Object> review = Map.of(
                    AuthoringState.REVIEW_DECISION, task.reviewDecision(),
                    AuthoringState.REVIEWER_ID, task.reviewerId(),
                    AuthoringState.REVIEWED_DRAFT_JSON, task.reviewDraftJson()
            );
            trace.record(task.id(), task.graphVersion(), runId, "APPROVAL_SUBMITTED",
                    Node.HUMAN_REVIEW.id, null, null, task.reviewDecision(), null,
                    task.reviewerId(), Map.of(
                            "draftHash", blank(trace.fingerprint(task.reviewDraftJson())),
                            "hasComment", StringUtils.hasText(task.reviewComment())
                    ));
            RunnableConfig resumeConfig = workflow.updateState(config, review, Node.HUMAN_REVIEW.id);
            resumeConfig = RunnableConfig.builder(resumeConfig)
                    .putMetadata(RUN_ID_METADATA, runId)
                    .build();
            trace.record(task.id(), task.graphVersion(), runId, "CHECKPOINT_RESUMED",
                    Node.HUMAN_REVIEW.id, Node.HUMAN_REVIEW.id,
                    AuthoringReviewDecision.APPROVE.name().equals(task.reviewDecision())
                            ? Node.PUBLISH.id : Node.REJECT.id,
                    task.reviewDecision(), null, task.reviewerId(),
                    Map.of("checkpointId", resumeConfig.checkPointId().orElse("")));
            AuthoringState result = workflow.invoke(GraphInput.resume(), resumeConfig).orElseThrow();
            trace.record(task.id(), task.graphVersion(), runId, "RUN_FINISHED", null,
                    null, null, result.string(AuthoringState.RESULT_STATUS),
                    trace.elapsedMs(runStarted),
                    task.reviewerId(), Map.of());
        } catch (Exception exception) {
            Throwable cause = rootCause(exception);
            trace.record(task.id(), task.graphVersion(), runId, "RUN_FAILED", null,
                    null, null, "ERROR", trace.elapsedMs(runStarted), task.reviewerId(),
                    Map.of("errorType", cause.getClass().getSimpleName()));
            if (cause instanceof AuthoringCancelledException cancelled) throw cancelled;
            throw new IllegalStateException("AI 出题人工审核恢复失败：" + concise(cause.getMessage()), cause);
        }
    }

    private CompiledGraph<AuthoringState> build(BaseCheckpointSaver saver) throws GraphStateException {
        StateGraph<AuthoringState> graph = new StateGraph<>(AuthoringState::new);
        graph.addNode(Node.GENERATE.id, node_async((state, config) -> runNode(
                Node.GENERATE, state, config, () -> generate(state, config))));
        graph.addNode(Node.VALIDATE.id, node_async((state, config) -> runNode(
                Node.VALIDATE, state, config, () -> validate(state))));
        graph.addNode(Node.VERIFY.id, node_async((state, config) -> runNode(
                Node.VERIFY, state, config, () -> verify(state, config))));
        graph.addNode(Node.REPAIR.id, node_async((state, config) -> runNode(
                Node.REPAIR, state, config, () -> repair(state, config))));
        graph.addNode(Node.PREPARE_REVIEW.id, node_async((state, config) -> runNode(
                Node.PREPARE_REVIEW, state, config, () -> prepareReview(state))));
        graph.addNode(Node.HUMAN_REVIEW.id, node_async((state, config) -> humanReview(state)));
        graph.addNode(Node.PUBLISH.id, node_async((state, config) -> runNode(
                Node.PUBLISH, state, config, () -> publish(state, config))));
        graph.addNode(Node.REJECT.id, node_async((state, config) -> runNode(
                Node.REJECT, state, config, () -> reject(state))));
        graph.addNode(Node.FAIL.id, node_async((state, config) -> runNode(
                Node.FAIL, state, config, () -> fail(state))));
        graph.addEdge(START, Node.GENERATE.id);
        graph.addEdge(Node.GENERATE.id, Node.VALIDATE.id);
        graph.addConditionalEdges(Node.VALIDATE.id, command_async((state, config) ->
                new Command(routeAfterValidation(state, config))), Map.of(
                Node.VERIFY.id, Node.VERIFY.id,
                Node.REPAIR.id, Node.REPAIR.id,
                Node.FAIL.id, Node.FAIL.id
        ));
        graph.addConditionalEdges(Node.VERIFY.id, command_async((state, config) ->
                new Command(routeAfterVerification(state, config))), Map.of(
                Node.PREPARE_REVIEW.id, Node.PREPARE_REVIEW.id,
                Node.REPAIR.id, Node.REPAIR.id,
                Node.FAIL.id, Node.FAIL.id
        ));
        graph.addEdge(Node.REPAIR.id, Node.VALIDATE.id);
        graph.addEdge(Node.PREPARE_REVIEW.id, Node.HUMAN_REVIEW.id);
        graph.addConditionalEdges(Node.HUMAN_REVIEW.id, command_async((state, config) ->
                new Command(routeAfterHumanReview(state, config))), Map.of(
                Node.PUBLISH.id, Node.PUBLISH.id,
                Node.REJECT.id, Node.REJECT.id
        ));
        graph.addEdge(Node.PUBLISH.id, END);
        graph.addEdge(Node.REJECT.id, END);
        graph.addEdge(Node.FAIL.id, END);
        return graph.compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .interruptBefore(Node.HUMAN_REVIEW.id)
                .graphId(properties.getGraphVersion())
                .recursionLimit(Math.max(16, 6 + (properties.getMaxRepairCount() + 1) * 3))
                .releaseThread(false)
                .build());
    }

    private Map<String, Object> generate(AuthoringState state, RunnableConfig config) throws Exception {
        Instant started = trace.started();
        try {
            AuthoringDraftModel.GenerationOutcome outcome = draftModel.generate(readRequest(state));
            trace.record(state.taskId(), properties.getGraphVersion(), runId(config), "LLM_CALL",
                    Node.GENERATE.id, null, null, "SUCCESS", trace.elapsedMs(started), null,
                    modelTraceDetails(outcome, "generate", null));
            return draftUpdate(state.taskId(), outcome, repairCount(state));
        } catch (Exception exception) {
            trace.record(state.taskId(), properties.getGraphVersion(), runId(config), "LLM_CALL",
                    Node.GENERATE.id, null, null, "ERROR", trace.elapsedMs(started), null,
                    Map.of("operation", "generate", "errorType", exception.getClass().getSimpleName()));
            throw exception;
        }
    }

    private Map<String, Object> validate(AuthoringState state) throws Exception {
        return Map.of(
                AuthoringState.ERRORS_JSON, write(validator.validate(readDraft(state))),
                AuthoringState.SANDBOX_PASSED, false
        );
    }

    private Map<String, Object> verify(AuthoringState state, RunnableConfig config) throws Exception {
        Instant started = trace.started();
        try {
            AuthoringProblemDraft draft = readDraft(state);
            AuthoringSandboxVerifier.SandboxVerification result = sandboxVerifier.verify(draft);
            trace.record(state.taskId(), properties.getGraphVersion(), runId(config), "TOOL_CALL",
                    Node.VERIFY.id, null, null, result.passed() ? "SUCCESS" : "FAILED",
                    trace.elapsedMs(started), null,
                    Map.of("tool", "code_sandbox", "caseCount", draft.judgeCase().size(),
                            "errorCount", result.errors().size()));
            return Map.of(
                    AuthoringState.SANDBOX_PASSED, result.passed(),
                    AuthoringState.ERRORS_JSON, write(result.errors())
            );
        } catch (Exception exception) {
            trace.record(state.taskId(), properties.getGraphVersion(), runId(config), "TOOL_CALL",
                    Node.VERIFY.id, null, null, "ERROR", trace.elapsedMs(started), null,
                    Map.of("tool", "code_sandbox", "errorType", exception.getClass().getSimpleName()));
            throw exception;
        }
    }

    private Map<String, Object> repair(AuthoringState state, RunnableConfig config) throws Exception {
        int repairCount = repairCount(state) + 1;
        Instant started = trace.started();
        try {
            AuthoringDraftModel.GenerationOutcome outcome = draftModel.repair(
                    readRequest(state), readDraft(state), readErrors(state));
            trace.record(state.taskId(), properties.getGraphVersion(), runId(config), "LLM_CALL",
                    Node.REPAIR.id, null, null, "SUCCESS", trace.elapsedMs(started), null,
                    modelTraceDetails(outcome, "repair", repairCount));
            return draftUpdate(state.taskId(), outcome, repairCount);
        } catch (Exception exception) {
            trace.record(state.taskId(), properties.getGraphVersion(), runId(config), "LLM_CALL",
                    Node.REPAIR.id, null, null, "ERROR", trace.elapsedMs(started), null,
                    Map.of("operation", "repair", "repairCount", repairCount,
                            "errorType", exception.getClass().getSimpleName()));
            throw exception;
        }
    }

    private Map<String, Object> prepareReview(AuthoringState state) throws Exception {
        AuthoringProblemDraft draft = readDraft(state);
        ProblemDraftArtifact artifact = new ProblemDraftArtifact(
                draft, new AuthoringValidation("java", draft.judgeCase().size(), true, List.of()));
        repository.awaitReview(state.taskId(), write(artifact), repairCount(state));
        metrics.task("review_required");
        return Map.of(AuthoringState.RESULT_STATUS, "REVIEW_REQUIRED");
    }

    private Map<String, Object> humanReview(AuthoringState state) {
        reviewDecision(state);
        return Map.of();
    }

    private Map<String, Object> publish(AuthoringState state, RunnableConfig config) throws Exception {
        if (reviewDecision(state) != AuthoringReviewDecision.APPROVE) {
            throw new IllegalStateException("只有人工审核通过的草稿才能发布");
        }
        long reviewerId = state.longValue(AuthoringState.REVIEWER_ID);
        if (reviewerId <= 0) throw new IllegalStateException("发布节点缺少审核人身份");
        String draftJson = state.string(AuthoringState.REVIEWED_DRAFT_JSON);
        AuthoringProblemDraft draft = objectMapper.readValue(draftJson, AuthoringProblemDraft.class);
        String runId = runId(config);
        String draftHash = blank(trace.fingerprint(draftJson));
        trace.record(state.taskId(), properties.getGraphVersion(), runId, "WRITE_STARTED",
                Node.PUBLISH.id, null, null, "RUNNING", null, reviewerId,
                Map.of("operation", "create_question", "draftHash", draftHash));
        long questionId;
        Instant started = trace.started();
        try {
            questionId = questionPublisher.publish(state.taskId(), reviewerId, draftJson, draft);
            trace.record(state.taskId(), properties.getGraphVersion(), runId, "WRITE_COMPLETED",
                    Node.PUBLISH.id, null, null, "SUCCESS", trace.elapsedMs(started), reviewerId,
                    Map.of("operation", "create_question", "draftHash", draftHash,
                            "questionId", Long.toString(questionId), "idempotent", true));
        } catch (Exception exception) {
            trace.record(state.taskId(), properties.getGraphVersion(), runId, "WRITE_COMPLETED",
                    Node.PUBLISH.id, null, null, "ERROR", trace.elapsedMs(started), reviewerId,
                    Map.of("operation", "create_question", "draftHash", draftHash,
                            "errorType", exception.getClass().getSimpleName()));
            throw exception;
        }
        if (!repository.markPublished(state.taskId(), questionId)) {
            throw new IllegalStateException("题目已写入，但任务发布状态尚未确认");
        }
        metrics.task("published");
        return Map.of(
                AuthoringState.RESULT_STATUS, "PUBLISHED",
                AuthoringState.PUBLISHED_QUESTION_ID, questionId
        );
    }

    private Map<String, Object> reject(AuthoringState state) {
        if (reviewDecision(state) != AuthoringReviewDecision.REJECT) {
            throw new IllegalStateException("审核决定不是驳回");
        }
        if (!repository.markRejected(state.taskId())) {
            throw new IllegalStateException("无法保存人工驳回状态");
        }
        metrics.task("rejected");
        return Map.of(AuthoringState.RESULT_STATUS, "REJECTED");
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

    private String routeAfterValidation(AuthoringState state, RunnableConfig config) throws Exception {
        List<String> errors = readErrors(state);
        String target = route(errors.isEmpty(), Node.VERIFY, state);
        traceEdge(state, config, Node.VALIDATE, target,
                Map.of("errorCount", errors.size(), "repairCount", repairCount(state)));
        return target;
    }

    private String routeAfterVerification(AuthoringState state, RunnableConfig config) {
        boolean passed = state.bool(AuthoringState.SANDBOX_PASSED);
        String target = route(passed, Node.PREPARE_REVIEW, state);
        traceEdge(state, config, Node.VERIFY, target,
                Map.of("sandboxPassed", passed, "repairCount", repairCount(state)));
        return target;
    }

    private String routeAfterHumanReview(AuthoringState state, RunnableConfig config) {
        AuthoringReviewDecision decision = reviewDecision(state);
        String target = decision == AuthoringReviewDecision.APPROVE
                ? Node.PUBLISH.id : Node.REJECT.id;
        traceEdge(state, config, Node.HUMAN_REVIEW, target,
                Map.of("decision", decision.name()));
        return target;
    }

    private String route(boolean passed, Node success, AuthoringState state) {
        if (passed) return success.id;
        return repairCount(state) < properties.getMaxRepairCount() ? Node.REPAIR.id : Node.FAIL.id;
    }

    private void traceEdge(AuthoringState state,
                           RunnableConfig config,
                           Node source,
                           String target,
                           Map<String, ?> details) {
        trace.record(state.taskId(), properties.getGraphVersion(), runId(config), "EDGE_ROUTED",
                null, source.id, target, "SELECTED", null, null, details);
    }

    private Map<String, Object> runNode(Node node,
                                        AuthoringState state,
                                        RunnableConfig config,
                                        CheckedSupplier action) throws Exception {
        Timer.Sample sample = metrics.start();
        Instant started = trace.started();
        int currentRepairs = repairCount(state);
        trace.record(state.taskId(), properties.getGraphVersion(), runId(config), "NODE_STARTED",
                node.id, null, null, "RUNNING", null, null,
                Map.of("repairCount", currentRepairs));
        try {
            checkCancelled(state.taskId());
            int startedRepairs = node == Node.REPAIR ? currentRepairs + 1 : currentRepairs;
            repository.updateStage(state.taskId(), node.stage, progress(node, currentRepairs, false), startedRepairs);
            Map<String, Object> update = action.get();
            int completedRepairs = number(update.get(AuthoringState.REPAIR_COUNT), startedRepairs);
            repository.updateStage(state.taskId(), node.stage, progress(node, currentRepairs, true), completedRepairs);
            metrics.stopNode(sample, node.id, "success");
            trace.record(state.taskId(), properties.getGraphVersion(), runId(config), "NODE_FINISHED",
                    node.id, null, null, "SUCCESS", trace.elapsedMs(started), null,
                    Map.of("repairCount", completedRepairs));
            return update;
        } catch (Exception exception) {
            metrics.stopNode(sample, node.id,
                    exception instanceof AuthoringCancelledException ? "cancelled" : "error");
            trace.record(state.taskId(), properties.getGraphVersion(), runId(config), "NODE_FINISHED",
                    node.id, null, null,
                    exception instanceof AuthoringCancelledException ? "CANCELLED" : "ERROR",
                    trace.elapsedMs(started), null,
                    Map.of("repairCount", currentRepairs,
                            "errorType", exception.getClass().getSimpleName()));
            throw exception;
        }
    }

    private int progress(Node node, int repairCount, boolean completed) {
        if (node == Node.GENERATE) return completed ? 15 : 5;
        if (node == Node.PREPARE_REVIEW) return 95;
        if (node == Node.PUBLISH || node == Node.REJECT || node == Node.FAIL) {
            return completed ? 99 : 96;
        }
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

    private RunnableConfig config(long taskId, String runId) {
        return RunnableConfig.builder(config(taskId))
                .putMetadata(RUN_ID_METADATA, runId)
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

    private String newRunId() {
        String value = trace.newRunId();
        return StringUtils.hasText(value) ? value : UUID.randomUUID().toString();
    }

    private static String runId(RunnableConfig config) {
        return config.metadata(RUN_ID_METADATA).map(Object::toString).orElse("unknown-run");
    }

    private static AuthoringReviewDecision reviewDecision(AuthoringState state) {
        String value = state.string(AuthoringState.REVIEW_DECISION);
        try {
            return AuthoringReviewDecision.valueOf(value);
        } catch (Exception exception) {
            throw new IllegalStateException("工作流正在等待有效的人工审核决定");
        }
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static Map<String, Object> modelTraceDetails(AuthoringDraftModel.GenerationOutcome outcome,
                                                          String operation,
                                                          Integer repairCount) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("model", blank(outcome.modelName()));
        details.put("promptVersion", blank(outcome.promptVersion()));
        details.put("operation", operation);
        if (repairCount != null) details.put("repairCount", repairCount);
        if (outcome.promptTokens() != null) details.put("promptTokens", outcome.promptTokens());
        if (outcome.completionTokens() != null) {
            details.put("completionTokens", outcome.completionTokens());
        }
        if (outcome.promptTokens() != null && outcome.completionTokens() != null) {
            details.put("totalTokens", outcome.promptTokens() + outcome.completionTokens());
        }
        return details;
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
        PREPARE_REVIEW("prepare_review", AuthoringStage.AWAITING_REVIEW),
        HUMAN_REVIEW("human_review", AuthoringStage.AWAITING_REVIEW),
        PUBLISH("publish_question", AuthoringStage.PUBLISHING),
        REJECT("reject_draft", AuthoringStage.REJECTED),
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

package com.qwerlty.myojbackendaiservice.authoring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.authoring.api.AuthoringTaskPage;
import com.qwerlty.myojbackendaiservice.authoring.api.AuthoringTaskResult;
import com.qwerlty.myojbackendaiservice.authoring.api.AuthoringTaskView;
import com.qwerlty.myojbackendaiservice.authoring.api.AuthoringTraceEventView;
import com.qwerlty.myojbackendaiservice.authoring.api.CreateAuthoringTaskRequest;
import com.qwerlty.myojbackendaiservice.authoring.api.ReviewAuthoringTaskRequest;
import com.qwerlty.myojbackendaiservice.authoring.graph.AuthoringCancelledException;
import com.qwerlty.myojbackendaiservice.authoring.graph.AuthoringDraftValidator;
import com.qwerlty.myojbackendaiservice.authoring.graph.QuestionAuthoringGraph;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringProblemDraft;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringReviewDecision;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringTask;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringTaskStatus;
import com.qwerlty.myojbackendaiservice.authoring.model.ProblemDraftArtifact;
import com.qwerlty.myojbackendaiservice.authoring.repository.AuthoringTaskRepository;
import com.qwerlty.myojbackendaiservice.authoring.repository.AuthoringTraceRecorder;
import com.qwerlty.myojbackendaiservice.common.ApiException;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.observability.AiMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

@Service
public class AuthoringTaskService {

    private static final Logger log = LoggerFactory.getLogger(AuthoringTaskService.class);

    private final AuthoringTaskRepository repository;
    private final QuestionAuthoringGraph graph;
    private final AuthoringDraftValidator draftValidator;
    private final AuthoringTraceRecorder traceRecorder;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor executor;
    private final AiAgentProperties.Authoring properties;
    private final AiMetrics metrics;
    private final Map<Long, Future<?>> futures = new ConcurrentHashMap<>();
    private final java.util.Set<Long> activeTasks = ConcurrentHashMap.newKeySet();

    public AuthoringTaskService(AuthoringTaskRepository repository,
                                QuestionAuthoringGraph graph,
                                AuthoringDraftValidator draftValidator,
                                AuthoringTraceRecorder traceRecorder,
                                ObjectMapper objectMapper,
                                @Qualifier("aiAuthoringExecutor") ThreadPoolTaskExecutor executor,
                                AiAgentProperties properties,
                                AiMetrics metrics) {
        this.repository = repository;
        this.graph = graph;
        this.draftValidator = draftValidator;
        this.traceRecorder = traceRecorder;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.properties = properties.getAuthoring();
        this.metrics = metrics;
    }

    public AuthoringTaskView create(long userId, CreateAuthoringTaskRequest request, String idempotencyKey) {
        ensureEnabled();
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > 128) {
            throw ApiException.badRequest("X-Idempotency-Key 不能为空且长度不能超过 128");
        }
        AuthoringTask task = repository.create(
                userId,
                null,
                idempotencyKey.trim(),
                write(request.requirements()),
                properties.getPromptVersion(),
                properties.getGraphVersion()
        );
        submitIfNeeded(task);
        return toView(task);
    }

    public AuthoringTaskView get(long userId, long taskId) {
        return toView(requireOwnedTask(userId, taskId));
    }

    public AuthoringTaskPage history(long userId, int current, int pageSize, String type) {
        if (current < 1) throw ApiException.badRequest("current 必须大于 0");
        if (pageSize < 1 || pageSize > properties.getPageSizeLimit()) {
            throw ApiException.badRequest("pageSize 必须在 1 到 " + properties.getPageSizeLimit() + " 之间");
        }
        if (StringUtils.hasText(type) && !"PROBLEM_DRAFT".equalsIgnoreCase(type)) {
            throw ApiException.badRequest("首版仅支持 PROBLEM_DRAFT 任务");
        }
        int offset = Math.toIntExact((long) (current - 1) * pageSize);
        List<AuthoringTaskView> records = repository.listByUser(userId, offset, pageSize)
                .stream().map(this::toView).toList();
        return new AuthoringTaskPage(records, repository.countByUser(userId), current, pageSize);
    }

    public List<AuthoringTraceEventView> trace(long userId, long taskId) {
        requireOwnedTask(userId, taskId);
        return traceRecorder.list(taskId);
    }

    public AuthoringTaskView cancel(long userId, long taskId) {
        AuthoringTask task = requireOwnedTask(userId, taskId);
        if (StringUtils.hasText(task.reviewDecision())) {
            throw ApiException.badRequest("人工审核已经提交，发布或驳回处理不能取消");
        }
        if (!task.status().terminal()) {
            repository.requestCancel(taskId);
            Future<?> future = futures.get(taskId);
            if (future != null) future.cancel(true);
            repository.markCancelled(taskId);
            metrics.task("cancelled");
        }
        return toView(repository.findById(taskId).orElseThrow());
    }

    public AuthoringTaskView review(long userId, long taskId, ReviewAuthoringTaskRequest request) {
        ensureEnabled();
        AuthoringTask task = requireOwnedTask(userId, taskId);
        ProblemDraftArtifact artifact = readArtifact(task);
        AuthoringProblemDraft reviewedDraft = request.draft() == null ? artifact.draft() : request.draft();
        String reviewedDraftJson = writeValue(reviewedDraft, "人工审核草稿无法序列化");
        String comment = normalizeComment(request.comment());

        if (request.decision() == AuthoringReviewDecision.APPROVE) {
            List<String> errors = draftValidator.validate(reviewedDraft);
            if (!errors.isEmpty()) {
                throw ApiException.badRequest("人工审核后的草稿不合法：" + String.join("；", errors));
            }
            ensureVerifiedJudgePayloadUnchanged(artifact.draft(), reviewedDraft);
        }

        if (task.status() == AuthoringTaskStatus.PUBLISHED
                || task.status() == AuthoringTaskStatus.REJECTED) {
            ensureSameReview(task, request.decision(), reviewedDraftJson, comment);
            return toView(task);
        }
        if (!properties.getGraphVersion().equals(task.graphVersion())) {
            throw ApiException.badRequest("该任务使用旧版 Graph，请从任务历史创建重试任务后再审核");
        }
        if (task.status() != AuthoringTaskStatus.REVIEW_REQUIRED) {
            throw ApiException.badRequest("任务当前不在等待人工审核状态");
        }
        if (StringUtils.hasText(task.reviewDecision())) {
            ensureSameReview(task, request.decision(), reviewedDraftJson, comment);
        }

        String reviewedResultJson = writeValue(
                new ProblemDraftArtifact(reviewedDraft, artifact.validation()),
                "人工审核结果无法序列化");
        boolean submitted = repository.submitReview(
                taskId,
                request.decision().name(),
                reviewedDraftJson,
                reviewedResultJson,
                userId,
                comment
        );
        if (!submitted) {
            AuthoringTask current = requireOwnedTask(userId, taskId);
            if (current.status() == AuthoringTaskStatus.PUBLISHED
                    || current.status() == AuthoringTaskStatus.REJECTED) {
                ensureSameReview(current, request.decision(), reviewedDraftJson, comment);
                return toView(current);
            }
            throw ApiException.badRequest("任务审核状态已变化，请刷新后重试");
        }

        if (activeTasks.add(taskId)) run(taskId);
        return toView(repository.findById(taskId).orElseThrow());
    }

    public AuthoringTaskView retry(long userId, long taskId) {
        ensureEnabled();
        AuthoringTask source = requireOwnedTask(userId, taskId);
        if (!source.status().terminal()) {
            throw ApiException.badRequest("运行中的任务不能重试，请先取消任务");
        }
        AuthoringTask retry = repository.create(
                userId,
                source.id(),
                "retry-" + source.id() + "-" + UUID.randomUUID(),
                source.requestJson(),
                properties.getPromptVersion(),
                properties.getGraphVersion()
        );
        submitIfNeeded(retry);
        return toView(retry);
    }

    public int recoverInterruptedTasks() {
        if (!properties.isEnabled()) return 0;
        LocalDateTime staleBefore = LocalDateTime.now().minus(properties.getStaleAfter());
        List<AuthoringTask> tasks = repository.listRecoverable(staleBefore, properties.getPageSizeLimit());
        tasks.forEach(this::submitIfNeeded);
        if (!tasks.isEmpty()) log.info("Submitted {} recoverable AI authoring tasks", tasks.size());
        return tasks.size();
    }

    private void submitIfNeeded(AuthoringTask task) {
        if (task.status().terminal() || task.cancelRequested() || !activeTasks.add(task.id())) return;
        FutureTask<Void> future = new FutureTask<>(() -> {
            run(task.id());
            return null;
        });
        futures.put(task.id(), future);
        try {
            executor.execute(future);
        } catch (RuntimeException exception) {
            futures.remove(task.id(), future);
            activeTasks.remove(task.id());
            repository.fail(task.id(), "QUEUE_REJECTED", "AI 任务队列繁忙，请稍后重试", task.repairCount());
            metrics.task("queue_rejected");
            throw ApiException.tooManyRequests("AI 任务队列繁忙，请稍后重试");
        }
    }

    private void run(long taskId) {
        try {
            AuthoringTask task = repository.findById(taskId).orElseThrow();
            if (task.status().terminal() || task.cancelRequested()) return;
            if (!properties.getGraphVersion().equals(task.graphVersion())) {
                repository.fail(taskId, "GRAPH_VERSION_MISMATCH",
                        "任务 Graph 版本已过期，请从任务历史创建重试任务", task.repairCount());
                metrics.task("graph_version_mismatch");
                return;
            }
            repository.markRunning(taskId, task.stage(), Math.max(task.progress(), 1));
            task = repository.findById(taskId).orElseThrow();
            if (task.status().terminal() || task.cancelRequested()) return;
            if (StringUtils.hasText(task.reviewDecision())) graph.resumeReview(task);
            else graph.execute(task);
        } catch (AuthoringCancelledException exception) {
            repository.markCancelled(taskId);
        } catch (Exception exception) {
            if (repository.isCancelRequested(taskId) || Thread.currentThread().isInterrupted()) {
                repository.markCancelled(taskId);
            } else {
                AuthoringTask current = repository.findById(taskId).orElse(null);
                int repairCount = current == null ? 0 : current.repairCount();
                if (current != null && StringUtils.hasText(current.reviewDecision())) {
                    repository.returnToReview(taskId, "PUBLISH_FAILED", concise(exception.getMessage()));
                    metrics.task("publish_failed");
                } else {
                    repository.fail(taskId, "EXECUTION_FAILED", concise(exception.getMessage()), repairCount);
                    metrics.task("failed");
                }
                log.error("AI authoring task {} failed", taskId, exception);
            }
        } finally {
            futures.remove(taskId);
            activeTasks.remove(taskId);
        }
    }

    private AuthoringTask requireOwnedTask(long userId, long taskId) {
        AuthoringTask task = repository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("任务不存在"));
        if (task.userId() != userId) throw ApiException.notFound("任务不存在");
        return task;
    }

    private AuthoringTaskView toView(AuthoringTask task) {
        AuthoringTaskResult result = null;
        if (StringUtils.hasText(task.resultJson())) {
            try {
                result = new AuthoringTaskResult(
                        "PROBLEM_DRAFT",
                        1,
                        objectMapper.readValue(task.resultJson(), ProblemDraftArtifact.class)
                );
            } catch (Exception exception) {
                log.error("Cannot deserialize authoring task {} result", task.id(), exception);
                throw ApiException.operation("任务结果暂时无法读取");
            }
        }
        return new AuthoringTaskView(
                Long.toString(task.id()),
                task.sourceTaskId() == null ? null : Long.toString(task.sourceTaskId()),
                task.taskType(),
                task.status().name(),
                task.stage().name(),
                task.progress(),
                task.repairCount(),
                task.cancelRequested(),
                result,
                task.errorCode(),
                task.lastError(),
                task.modelName(),
                task.promptVersion(),
                task.graphVersion(),
                AuthoringTraceRecorder.traceId(task.id()),
                task.reviewDecision(),
                task.reviewerId() == null ? null : Long.toString(task.reviewerId()),
                task.reviewComment(),
                task.reviewedTime(),
                task.publishedQuestionId() == null ? null : Long.toString(task.publishedQuestionId()),
                task.createTime(),
                task.updateTime()
        );
    }

    private String write(Object value) {
        return writeValue(value, "出题需求无法序列化");
    }

    private String writeValue(Object value, String message) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw ApiException.badRequest(message);
        }
    }

    private ProblemDraftArtifact readArtifact(AuthoringTask task) {
        if (!StringUtils.hasText(task.resultJson())) {
            throw ApiException.badRequest("任务还没有可审核的题目草稿");
        }
        try {
            return objectMapper.readValue(task.resultJson(), ProblemDraftArtifact.class);
        } catch (Exception exception) {
            throw ApiException.operation("任务审核草稿暂时无法读取");
        }
    }

    private static void ensureSameReview(AuthoringTask task,
                                         AuthoringReviewDecision decision,
                                         String reviewedDraftJson,
                                         String comment) {
        if (!decision.name().equals(task.reviewDecision())
                || !reviewedDraftJson.equals(task.reviewDraftJson())
                || !comment.equals(normalizeComment(task.reviewComment()))) {
            throw ApiException.badRequest("人工审核已经提交，不能更改审核决定或发布内容");
        }
    }

    private static void ensureVerifiedJudgePayloadUnchanged(AuthoringProblemDraft verified,
                                                             AuthoringProblemDraft reviewed) {
        if (!verified.referenceCode().equals(reviewed.referenceCode())
                || !verified.judgeCase().equals(reviewed.judgeCase())
                || !verified.judgeConfig().equals(reviewed.judgeConfig())) {
            throw ApiException.badRequest(
                    "参考代码、测试用例和资源限制已经通过沙箱，审批时不能直接修改；请重新生成并验证草稿");
        }
    }

    private static String normalizeComment(String value) {
        return value == null ? "" : value.trim();
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw ApiException.serviceUnavailable("AI 出题功能当前未启用");
        }
    }

    private static String concise(String value) {
        if (!StringUtils.hasText(value)) return "AI 出题任务执行异常";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}

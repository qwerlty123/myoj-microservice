package com.qwerlty.myojbackendaiservice.authoring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.authoring.api.AuthoringTaskPage;
import com.qwerlty.myojbackendaiservice.authoring.api.AuthoringTaskResult;
import com.qwerlty.myojbackendaiservice.authoring.api.AuthoringTaskView;
import com.qwerlty.myojbackendaiservice.authoring.api.CreateAuthoringTaskRequest;
import com.qwerlty.myojbackendaiservice.authoring.graph.AuthoringCancelledException;
import com.qwerlty.myojbackendaiservice.authoring.graph.QuestionAuthoringGraph;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringTask;
import com.qwerlty.myojbackendaiservice.authoring.model.ProblemDraftArtifact;
import com.qwerlty.myojbackendaiservice.authoring.repository.AuthoringTaskRepository;
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
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor executor;
    private final AiAgentProperties.Authoring properties;
    private final AiMetrics metrics;
    private final Map<Long, Future<?>> futures = new ConcurrentHashMap<>();
    private final java.util.Set<Long> activeTasks = ConcurrentHashMap.newKeySet();

    public AuthoringTaskService(AuthoringTaskRepository repository,
                                QuestionAuthoringGraph graph,
                                ObjectMapper objectMapper,
                                @Qualifier("aiAgentExecutor") ThreadPoolTaskExecutor executor,
                                AiAgentProperties properties,
                                AiMetrics metrics) {
        this.repository = repository;
        this.graph = graph;
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

    public AuthoringTaskView cancel(long userId, long taskId) {
        AuthoringTask task = requireOwnedTask(userId, taskId);
        if (!task.status().terminal()) {
            repository.requestCancel(taskId);
            Future<?> future = futures.get(taskId);
            if (future != null) future.cancel(true);
            repository.markCancelled(taskId);
            metrics.task("cancelled");
        }
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
            repository.markRunning(taskId, task.stage(), Math.max(task.progress(), 1));
            task = repository.findById(taskId).orElseThrow();
            if (task.status().terminal() || task.cancelRequested()) return;
            graph.execute(task);
        } catch (AuthoringCancelledException exception) {
            repository.markCancelled(taskId);
        } catch (Exception exception) {
            if (repository.isCancelRequested(taskId) || Thread.currentThread().isInterrupted()) {
                repository.markCancelled(taskId);
            } else {
                AuthoringTask current = repository.findById(taskId).orElse(null);
                int repairCount = current == null ? 0 : current.repairCount();
                repository.fail(taskId, "EXECUTION_FAILED", concise(exception.getMessage()), repairCount);
                metrics.task("failed");
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
                task.createTime(),
                task.updateTime()
        );
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw ApiException.badRequest("出题需求无法序列化");
        }
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

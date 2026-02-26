package com.qwerlty.myojbackendaiservice.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.chat.agent.ChatEventSink;
import com.qwerlty.myojbackendaiservice.chat.agent.QuestionTutorAgent;
import com.qwerlty.myojbackendaiservice.chat.agent.TutorAnswer;
import com.qwerlty.myojbackendaiservice.chat.client.QuestionContextClient;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatMessage;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatMessageView;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatMeta;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatSendRequest;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatSession;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatSessionRequest;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatSessionView;
import com.qwerlty.myojbackendaiservice.chat.model.ChatMode;
import com.qwerlty.myojbackendaiservice.chat.model.ChatSessionStatus;
import com.qwerlty.myojbackendaiservice.chat.model.QuestionContext;
import com.qwerlty.myojbackendaiservice.chat.repository.AiChatRepository;
import com.qwerlty.myojbackendaiservice.common.ApiException;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final AiChatRepository repository;
    private final QuestionContextClient questionClient;
    private final QuestionTutorAgent tutorAgent;
    private final ChatSafetyPolicy safetyPolicy;
    private final AiAgentProperties.Chat properties;
    private final ThreadPoolTaskExecutor executor;
    private final ObjectMapper objectMapper;
    private final ReentrantLock[] sessionLocks = createLocks(1_024);

    public AiChatService(AiChatRepository repository,
                         QuestionContextClient questionClient,
                         QuestionTutorAgent tutorAgent,
                         ChatSafetyPolicy safetyPolicy,
                         AiAgentProperties properties,
                         @Qualifier("aiAgentExecutor") ThreadPoolTaskExecutor executor,
                         ObjectMapper objectMapper) {
        this.repository = repository;
        this.questionClient = questionClient;
        this.tutorAgent = tutorAgent;
        this.safetyPolicy = safetyPolicy;
        this.properties = properties.getChat();
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    public AiChatSessionView getSession(long userId, AiChatSessionRequest request) {
        checkEnabled();
        requireQuestion(request.questionId());
        AiChatSession session = repository.getOrCreateSession(
                userId, request.questionId(), properties.getRetentionDays());
        archiveIfExpired(session);
        String disableReason = findDisableReason(userId, request.questionId());
        int status = resolveStatus(session, disableReason);
        repository.updateSessionAccess(session.id(), status, disableReason);
        List<AiChatMessageView> messages = repository.listMessages(session.id()).stream()
                .map(message -> toView(message, null)).toList();
        return new AiChatSessionView(Long.toString(session.id()), status, session.mode(), !StringUtils.hasText(disableReason),
                disableReason, messages);
    }

    public boolean clearSession(long userId, AiChatSessionRequest request) {
        checkEnabled();
        repository.findSession(userId, request.questionId())
                .ifPresent(session -> withLock(session.id(), () -> {
                    repository.clearMessages(session.id(), properties.getRetentionDays());
                    return null;
                }));
        return true;
    }

    public AiChatMessageView chat(long userId, AiChatSendRequest request) {
        return doChat(userId, request, null);
    }

    public SseEmitter streamChat(long userId, AiChatSendRequest request) {
        checkEnabled();
        SseEmitter emitter = new SseEmitter(properties.getTimeout().toMillis());
        AtomicBoolean closed = new AtomicBoolean(false);
        Future<?>[] task = new Future<?>[1];
        emitter.onCompletion(() -> cancel(closed, task));
        emitter.onError(error -> cancel(closed, task));
        emitter.onTimeout(() -> {
            if (closed.compareAndSet(false, true)) {
                safeSend(emitter, "error", "AI 回复超时，请稍后重试");
                if (task[0] != null) {
                    task[0].cancel(true);
                }
                safeComplete(emitter);
            }
        });
        try {
            task[0] = executor.submit(() -> {
                try {
                    doChat(userId, request, (name, data) -> {
                        if (!closed.get()) {
                            safeSend(emitter, name, data);
                        }
                    });
                    if (closed.compareAndSet(false, true)) {
                        safeComplete(emitter);
                    }
                } catch (Exception exception) {
                    log.warn("AI stream failed: {}", exception.getMessage());
                    if (closed.compareAndSet(false, true)) {
                        safeSend(emitter, "error", userMessage(exception));
                        safeComplete(emitter);
                    }
                }
            });
        } catch (TaskRejectedException exception) {
            safeSend(emitter, "error", "当前 AI 请求较多，请稍后重试");
            safeComplete(emitter);
        }
        return emitter;
    }

    public int archiveExpiredSessions() {
        return repository.archiveExpiredSessions();
    }

    private AiChatMessageView doChat(long userId, AiChatSendRequest request, ChatEventSink sink) {
        checkEnabled();
        validateRequest(request);
        QuestionContext question = requireQuestion(request.questionId());
        AiChatSession session = repository.getOrCreateSession(
                userId, question.id(), properties.getRetentionDays());
        return withLock(session.id(), () -> {
            String disableReason = findDisableReason(userId, question.id());
            if (StringUtils.hasText(disableReason)) {
                repository.updateSessionAccess(session.id(), ChatSessionStatus.DISABLED.value(), disableReason);
                throw ApiException.forbidden(disableReason);
            }
            checkInputSafety(userId, session.id(), request.message());
            List<AiChatMessage> history = repository.listRecentMessages(
                    session.id(), properties.getMaxHistoryMessages());
            long startedAt = System.currentTimeMillis();
            String traceId = UUID.randomUUID().toString();
            TutorAnswer answer = tutorAgent.answer(userId, session, question, request, history, sink);
            long duration = System.currentTimeMillis() - startedAt;
            String toolCalls = writeJson(answer.toolEvents());
            AiChatMessage assistant = repository.saveRound(session.id(), request.resolvedMode(),
                    request.message().trim(), answer.content(), toolCalls, properties.getRetentionDays(),
                    new AiChatRepository.MessageMetadata(traceId, answer.modelName(), answer.promptVersion(),
                            duration, answer.promptTokens(), answer.completionTokens()));
            AiChatMessageView view = toView(assistant, duration);
            if (sink != null) {
                sink.emit("meta", new AiChatMeta(Long.toString(session.id()), Long.toString(assistant.id()), assistant.mode()));
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("messageId", view.id());
                done.put("mode", view.mode());
                done.put("content", view.content());
                done.put("rawContent", view.rawContent());
                done.put("finalContent", view.finalContent());
                done.put("reasoningDurationMs", view.reasoningDurationMs());
                done.put("traceId", view.traceId());
                done.put("modelName", view.modelName());
                done.put("promptVersion", view.promptVersion());
                done.put("promptTokens", view.promptTokens());
                done.put("completionTokens", view.completionTokens());
                done.put("toolCalls", view.toolCalls());
                sink.emit("done", done);
            }
            return view;
        });
    }

    private void validateRequest(AiChatSendRequest request) {
        if (request.message().length() > properties.getMaxMessageLength()) {
            throw ApiException.badRequest("消息不能超过 " + properties.getMaxMessageLength() + " 个字符");
        }
        if (request.userCode() != null && request.userCode().length() > properties.getMaxUserCodeLength()) {
            throw ApiException.badRequest("代码不能超过 " + properties.getMaxUserCodeLength() + " 个字符");
        }
    }

    private void checkInputSafety(long userId, long sessionId, String message) {
        if (safetyPolicy.containsPromptInjection(message)) {
            repository.saveViolation(userId, sessionId, null, "prompt_injection", truncate(message, 500));
            throw ApiException.forbidden("检测到疑似提示词注入行为，请求已被拦截");
        }
        String word = safetyPolicy.matchedSensitiveWord(message, repository.listSensitiveWords());
        if (word != null) {
            repository.saveViolation(userId, sessionId, null, "input_sensitive", truncate(message, 500));
            throw ApiException.forbidden("消息包含不允许的内容");
        }
    }

    private QuestionContext requireQuestion(long questionId) {
        try {
            QuestionContext question = questionClient.getQuestion(questionId);
            if (question != null && question.id() != null) {
                return question;
            }
        } catch (Exception exception) {
            log.debug("Question lookup failed for {}: {}", questionId, exception.getMessage());
        }
        throw ApiException.notFound("题目不存在");
    }

    private String findDisableReason(long userId, long questionId) {
        List<AiChatRepository.DisableRule> rules = repository.listActiveDisableRules();
        String reason = matchRule(rules, "question", questionId);
        if (reason == null) reason = matchRule(rules, "user", userId);
        if (reason == null) reason = matchRule(rules, "global", null);
        return reason;
    }

    private String matchRule(List<AiChatRepository.DisableRule> rules, String type, Long id) {
        return rules.stream().filter(rule -> type.equalsIgnoreCase(rule.scopeType()))
                .filter(rule -> id == null || id.equals(rule.scopeId()))
                .map(AiChatRepository.DisableRule::reason).filter(StringUtils::hasText).findFirst().orElse(null);
    }

    private int resolveStatus(AiChatSession session, String disableReason) {
        if (StringUtils.hasText(disableReason)) {
            return ChatSessionStatus.DISABLED.value();
        }
        if (session.expireTime() != null && session.expireTime().isBefore(LocalDateTime.now())) {
            return ChatSessionStatus.ARCHIVED.value();
        }
        return session.status() == ChatSessionStatus.DISABLED.value()
                ? ChatSessionStatus.ACTIVE.value() : session.status();
    }

    private void archiveIfExpired(AiChatSession session) {
        if (session.status() == ChatSessionStatus.ACTIVE.value() && session.expireTime() != null
                && session.expireTime().isBefore(LocalDateTime.now())) {
            repository.updateSessionAccess(session.id(), ChatSessionStatus.ARCHIVED.value(), null);
        }
    }

    private AiChatMessageView toView(AiChatMessage message, Long duration) {
        return new AiChatMessageView(Long.toString(message.id()), message.role(), message.mode(), message.content(),
                message.content(), message.content(), message.toolEvents(), message.createTime(),
                duration == null ? message.latencyMs() : duration, message.traceId(), message.modelName(),
                message.promptVersion(), message.promptTokens(), message.completionTokens());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private <T> T withLock(long sessionId, SupplierWithException<T> action) {
        ReentrantLock lock = sessionLocks[Math.floorMod(Long.hashCode(sessionId), sessionLocks.length)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private void safeSend(SseEmitter emitter, String eventName, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(eventName).data(json));
            }
        } catch (Exception exception) {
            log.debug("SSE connection is no longer writable: {}", exception.getMessage());
        }
    }

    private static void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }

    private static void cancel(AtomicBoolean closed, Future<?>[] task) {
        closed.set(true);
        if (task[0] != null && !task[0].isDone()) {
            task[0].cancel(true);
        }
    }

    private static String userMessage(Exception exception) {
        return exception instanceof ApiException && StringUtils.hasText(exception.getMessage())
                ? exception.getMessage() : "AI 服务暂时不可用";
    }

    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private void checkEnabled() {
        if (!properties.isEnabled()) {
            throw ApiException.serviceUnavailable("AI 功能当前未开启");
        }
    }

    private static ReentrantLock[] createLocks(int size) {
        ReentrantLock[] locks = new ReentrantLock[size];
        for (int index = 0; index < size; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    @FunctionalInterface
    private interface SupplierWithException<T> {
        T get();
    }
}

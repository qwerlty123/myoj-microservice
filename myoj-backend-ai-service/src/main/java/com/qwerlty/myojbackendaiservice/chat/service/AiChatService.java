package com.qwerlty.myojbackendaiservice.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.chat.agent.ChatExecutionCancelledException;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final AiChatRepository repository;
    private final QuestionContextClient questionClient;
    private final QuestionTutorAgent tutorAgent;
    private final ChatSafetyPolicy safetyPolicy;
    private final AiAgentProperties.Chat properties;
    private final ThreadPoolTaskExecutor chatExecutor;
    private final ObjectMapper objectMapper;

    public AiChatService(AiChatRepository repository,
                         QuestionContextClient questionClient,
                         QuestionTutorAgent tutorAgent,
                         ChatSafetyPolicy safetyPolicy,
                         AiAgentProperties properties,
                         @Qualifier("aiChatExecutor") ThreadPoolTaskExecutor chatExecutor,
                         ObjectMapper objectMapper) {
        this.repository = repository;
        this.questionClient = questionClient;
        this.tutorAgent = tutorAgent;
        this.safetyPolicy = safetyPolicy;
        this.properties = properties.getChat();
        this.chatExecutor = chatExecutor;
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
                .ifPresent(session -> repository.clearMessages(session.id(), properties.getRetentionDays()));
        return true;
    }

    public AiChatMessageView chat(long userId, AiChatSendRequest request) {
        checkEnabled();
        ChatExecutionControl control = new ChatExecutionControl();
        Future<AiChatMessageView> future;
        try {
            future = chatExecutor.submit(() -> doChat(userId, request, null, control));
        } catch (TaskRejectedException exception) {
            throw ApiException.tooManyRequests("当前 AI 请求较多，请稍后重试");
        }
        try {
            return future.get(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            control.cancel();
            future.cancel(true);
            throw ApiException.serviceUnavailable("AI 回复超时，请稍后重试");
        } catch (InterruptedException exception) {
            control.cancel();
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("AI 回复已取消");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ChatExecutionCancelledException) {
                throw ApiException.serviceUnavailable("AI 回复已取消");
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw ApiException.serviceUnavailable("AI 服务暂时不可用");
        }
    }

    public SseEmitter streamChat(long userId, AiChatSendRequest request) {
        checkEnabled();
        SseEmitter emitter = new SseEmitter(properties.getTimeout().toMillis());
        AtomicBoolean closed = new AtomicBoolean(false);
        ChatExecutionControl control = new ChatExecutionControl();
        AtomicReference<Future<?>> task = new AtomicReference<>();
        emitter.onCompletion(() -> cancel(closed, control, task));
        emitter.onError(error -> cancel(closed, control, task));
        emitter.onTimeout(() -> {
            if (closed.compareAndSet(false, true)) {
                safeSend(emitter, "error", "AI 回复超时，请稍后重试");
                control.cancel();
                Future<?> future = task.get();
                if (future != null) {
                    future.cancel(true);
                }
                safeComplete(emitter);
            }
        });
        try {
            Future<?> submitted = chatExecutor.submit(() -> {
                try {
                    ChatEventSink sink = new ChatEventSink() {
                        @Override
                        public void emit(String name, Object data) {
                            control.checkCancelled();
                            if (closed.get() || !safeSend(emitter, name, data)) {
                                control.cancel();
                                throw new ChatExecutionCancelledException();
                            }
                        }

                        @Override
                        public boolean isCancelled() {
                            return control.isCancelled();
                        }
                    };
                    doChat(userId, request, sink, control);
                    if (closed.compareAndSet(false, true)) {
                        safeComplete(emitter);
                    }
                } catch (ChatExecutionCancelledException ignored) {
                    control.cancel();
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
            task.set(submitted);
            if (control.isCancelled()) {
                submitted.cancel(true);
            }
        } catch (TaskRejectedException exception) {
            closed.set(true);
            safeSend(emitter, "error", "当前 AI 请求较多，请稍后重试");
            safeComplete(emitter);
        }
        return emitter;
    }

    public int archiveExpiredSessions() {
        return repository.archiveExpiredSessions();
    }

    private AiChatMessageView doChat(long userId, AiChatSendRequest request,
                                     ChatEventSink sink, ChatExecutionControl control) {
        checkEnabled();
        validateRequest(request);
        control.checkCancelled();
        QuestionContext question = requireQuestion(request.questionId());
        AiChatSession session = repository.getOrCreateSession(
                userId, question.id(), properties.getRetentionDays());
        String disableReason = findDisableReason(userId, question.id());
        if (StringUtils.hasText(disableReason)) {
            repository.updateSessionAccess(session.id(), ChatSessionStatus.DISABLED.value(), disableReason);
            throw ApiException.forbidden(disableReason);
        }
        checkInputSafety(userId, session.id(), request.message());

        AiChatRepository.SessionClaim claim = repository.claimSession(
                session.id(), request.clientMessageId(),
                LocalDateTime.now().plus(properties.getTimeout()).plusSeconds(30),
                properties.getRetentionDays());
        if (claim.state() == AiChatRepository.ClaimState.COMPLETED) {
            AiChatMessageView completed = toView(claim.completedMessage(), null);
            emitCompleted(sink, session.id(), completed);
            return completed;
        }
        if (claim.state() == AiChatRepository.ClaimState.BUSY) {
            throw ApiException.tooManyRequests("当前会话已有回复正在生成，请等待完成");
        }

        control.register(session.id(), request.clientMessageId(), claim.requestToken());
        try {
            control.checkCancelled();
            List<AiChatMessage> history = repository.listRecentMessages(
                    session.id(), properties.getMaxHistoryMessages());
            long startedAt = System.currentTimeMillis();
            String traceId = UUID.randomUUID().toString();
            TutorAnswer answer = tutorAgent.answer(userId, session, question, request, history, sink);
            control.checkCancelled();

            disableReason = findDisableReason(userId, question.id());
            if (StringUtils.hasText(disableReason)) {
                repository.updateSessionAccess(session.id(), ChatSessionStatus.DISABLED.value(), disableReason);
                throw ApiException.forbidden(disableReason);
            }

            long duration = System.currentTimeMillis() - startedAt;
            String toolCalls = writeJson(answer.toolEvents());
            AiChatMessage assistant = repository.saveRoundIfCurrent(
                            session.id(), request.clientMessageId(), claim.sessionVersion(), claim.requestToken(),
                            request.resolvedMode(), request.message().trim(), answer.content(), toolCalls,
                            properties.getRetentionDays(),
                            new AiChatRepository.MessageMetadata(traceId, answer.modelName(), answer.promptVersion(),
                                    duration, answer.promptTokens(), answer.completionTokens()))
                    .orElseThrow(ChatExecutionCancelledException::new);
            control.completed();
            AiChatMessageView view = toView(assistant, duration);
            emitCompleted(sink, session.id(), view);
            return view;
        } catch (RuntimeException exception) {
            control.releaseClaim();
            throw exception;
        }
    }

    private void emitCompleted(ChatEventSink sink, long sessionId, AiChatMessageView view) {
        if (sink == null) {
            return;
        }
        sink.emit("meta", new AiChatMeta(Long.toString(sessionId), view.id(), view.mode()));
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

    private boolean safeSend(SseEmitter emitter, String eventName, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(eventName).data(json));
            }
            return true;
        } catch (Exception exception) {
            log.debug("SSE connection is no longer writable: {}", exception.getMessage());
            return false;
        }
    }

    private static void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }

    private static void cancel(AtomicBoolean closed, ChatExecutionControl control,
                               AtomicReference<Future<?>> task) {
        closed.set(true);
        control.cancel();
        Future<?> future = task.get();
        if (future != null && !future.isDone()) {
            future.cancel(true);
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

    private final class ChatExecutionControl {

        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<ClaimHandle> claim = new AtomicReference<>();

        private void register(long sessionId, String clientMessageId, long requestToken) {
            ClaimHandle handle = new ClaimHandle(sessionId, clientMessageId, requestToken);
            claim.set(handle);
            if (cancelled.get()) {
                if (claim.compareAndSet(handle, null)) {
                    release(handle);
                }
                throw new ChatExecutionCancelledException();
            }
        }

        private void completed() {
            claim.set(null);
        }

        private boolean isCancelled() {
            return cancelled.get() || Thread.currentThread().isInterrupted();
        }

        private void checkCancelled() {
            if (isCancelled()) {
                throw new ChatExecutionCancelledException();
            }
        }

        private void cancel() {
            cancelled.set(true);
            releaseClaim();
        }

        private void releaseClaim() {
            ClaimHandle handle = claim.getAndSet(null);
            if (handle != null) {
                release(handle);
            }
        }

        private void release(ClaimHandle handle) {
            repository.releaseSessionClaim(
                    handle.sessionId(), handle.clientMessageId(), handle.requestToken());
        }
    }

    private record ClaimHandle(long sessionId, String clientMessageId, long requestToken) {
    }
}

package com.qwerlty.myojbackendaiservice.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.chat.agent.QuestionTutorAgent;
import com.qwerlty.myojbackendaiservice.chat.agent.TutorAnswer;
import com.qwerlty.myojbackendaiservice.chat.client.QuestionContextClient;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatMessage;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatMessageView;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatSendRequest;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatSession;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatSessionRequest;
import com.qwerlty.myojbackendaiservice.chat.model.ChatMode;
import com.qwerlty.myojbackendaiservice.chat.model.ChatSessionStatus;
import com.qwerlty.myojbackendaiservice.chat.model.QuestionContext;
import com.qwerlty.myojbackendaiservice.chat.repository.AiChatRepository;
import com.qwerlty.myojbackendaiservice.common.ApiException;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.observability.AiMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiChatServiceConcurrencyTest {

    @Test
    void duplicateRequestAcrossServiceInstancesInvokesTutorOnlyOnce() throws Exception {
        FakeRepository repository = new FakeRepository();
        QuestionContextClient questionClient = new FakeQuestionClient();
        BlockingTutorAgent tutor = new BlockingTutorAgent(repository);
        ThreadPoolTaskExecutor chatExecutor = executor();
        AiChatService firstInstance = service(repository, questionClient, tutor, chatExecutor);
        AiChatService secondInstance = service(repository, questionClient, tutor, chatExecutor);
        AiChatSendRequest request = new AiChatSendRequest(
                "duplicate-request", 9L, "agent", "why", "java", "class Main {}", "WA", List.of());

        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<AiChatMessageView> first = callers.submit(() -> firstInstance.chat(7L, request));
            assertThat(tutor.firstCallEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<AiChatMessageView> second = callers.submit(() -> secondInstance.chat(7L, request));
            assertThat(repository.busyClaimObserved.await(1, TimeUnit.SECONDS)).isTrue();
            tutor.release.countDown();
            AiChatMessageView firstResult = first.get(3, TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(3, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);

            AiChatMessageView replay = secondInstance.chat(7L, request);
            assertThat(replay.id()).isEqualTo(firstResult.id());
        } finally {
            tutor.release.countDown();
            callers.shutdownNow();
            chatExecutor.shutdown();
        }

        assertThat(tutor.calls).hasValue(1);
        assertThat(repository.savedRounds).hasValue(1);
    }

    @Test
    void clearingSessionFencesAnAnswerThatWasAlreadyGenerating() throws Exception {
        FakeRepository repository = new FakeRepository();
        BlockingTutorAgent tutor = new BlockingTutorAgent(repository);
        ThreadPoolTaskExecutor chatExecutor = executor();
        AiChatService service = service(repository, new FakeQuestionClient(), tutor, chatExecutor);
        AiChatSendRequest request = new AiChatSendRequest(
                "clear-fence", 9L, "agent", "why", "java", null, null, List.of());

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<AiChatMessageView> inFlight = caller.submit(() -> service.chat(7L, request));
            assertThat(tutor.firstCallEntered.await(1, TimeUnit.SECONDS)).isTrue();
            service.clearSession(7L, new AiChatSessionRequest(9L));
            tutor.release.countDown();

            assertThatThrownBy(() -> inFlight.get(3, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
            assertThat(repository.savedRounds).hasValue(0);
        } finally {
            tutor.release.countDown();
            caller.shutdownNow();
            chatExecutor.shutdown();
        }
    }

    @Test
    void timeoutCancelsTheClaimAndCannotPersistALateAnswer() throws Exception {
        FakeRepository repository = new FakeRepository();
        BlockingTutorAgent tutor = new BlockingTutorAgent(repository);
        ThreadPoolTaskExecutor chatExecutor = executor();
        AiAgentProperties properties = new AiAgentProperties();
        properties.getChat().setTimeout(Duration.ofMillis(500));
        AiChatService service = service(
                repository, new FakeQuestionClient(), tutor, chatExecutor, properties);
        AiChatSendRequest request = new AiChatSendRequest(
                "timeout-fence", 9L, "agent", "why", "java", null, null, List.of());

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<AiChatMessageView> timedOut = caller.submit(() -> service.chat(7L, request));
            assertThat(tutor.firstCallEntered.await(250, TimeUnit.MILLISECONDS)).isTrue();
            assertThatThrownBy(() -> timedOut.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(ApiException.class)
                    .hasMessage("AI 回复超时，请稍后重试");
            assertThat(repository.claimReleased.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.savedRounds).hasValue(0);
        } finally {
            tutor.release.countDown();
            caller.shutdownNow();
            chatExecutor.shutdown();
        }
    }

    private static AiChatService service(AiChatRepository repository,
                                         QuestionContextClient questionClient,
                                         QuestionTutorAgent tutor,
                                         ThreadPoolTaskExecutor executor) {
        return service(repository, questionClient, tutor, executor, new AiAgentProperties());
    }

    private static AiChatService service(AiChatRepository repository,
                                         QuestionContextClient questionClient,
                                         QuestionTutorAgent tutor,
                                         ThreadPoolTaskExecutor executor,
                                         AiAgentProperties properties) {
        return new AiChatService(repository, questionClient, tutor, new ChatSafetyPolicy(),
                properties, executor, new ObjectMapper());
    }

    private static ThreadPoolTaskExecutor executor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(0);
        executor.initialize();
        return executor;
    }

    private static final class FakeQuestionClient implements QuestionContextClient {

        @Override
        public QuestionContext getQuestion(long questionId) {
            return new QuestionContext(questionId, "two sum", "content", "[]", "[]", "{}", 1);
        }

        @Override
        public List<com.qwerlty.myojbackendaiservice.chat.model.SubmissionContext> listSubmissions(
                com.qwerlty.myojbackendaiservice.chat.model.SubmissionQuery query) {
            return List.of();
        }
    }

    private static final class BlockingTutorAgent extends QuestionTutorAgent {

        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch firstCallEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingTutorAgent(AiChatRepository repository) {
            super(new org.springframework.ai.chat.model.ChatModel() {
                      @Override
                      public ChatResponse call(Prompt prompt) {
                          return null;
                      }
                  }, repository, null, new AiAgentProperties(), new ObjectMapper(),
                    new AiMetrics(new SimpleMeterRegistry()), "test-model");
        }

        @Override
        public TutorAnswer answer(long userId, AiChatSession session, QuestionContext question,
                                  AiChatSendRequest request, List<AiChatMessage> history,
                                  com.qwerlty.myojbackendaiservice.chat.agent.ChatEventSink sink) {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstCallEntered.countDown();
            }
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new TutorAnswer("answer", List.of(), "test-model", "test-v1", 1, 1);
        }
    }

    private static final class FakeRepository extends AiChatRepository {

        private final AtomicInteger messageIds = new AtomicInteger(100);
        private final AtomicInteger savedRounds = new AtomicInteger();
        private final CountDownLatch busyClaimObserved = new CountDownLatch(1);
        private final CountDownLatch claimReleased = new CountDownLatch(1);
        private final AiChatSession session = new AiChatSession(
                11L, 7L, 9L, ChatMode.AGENT.value(), ChatSessionStatus.ACTIVE.value(), null,
                LocalDateTime.now(), LocalDateTime.now().plusDays(30),
                LocalDateTime.now(), LocalDateTime.now());
        private long version;
        private long requestToken;
        private String activeRequestId;
        private String completedClientMessageId;
        private AiChatMessage completedMessage;

        private FakeRepository() {
            super(new JdbcTemplate());
        }

        @Override
        public AiChatSession getOrCreateSession(long userId, long questionId, int retentionDays) {
            return session;
        }

        @Override
        public Optional<AiChatSession> findSession(long userId, long questionId) {
            return Optional.of(session);
        }

        @Override
        public List<DisableRule> listActiveDisableRules() {
            return List.of();
        }

        @Override
        public List<String> listSensitiveWords() {
            return List.of();
        }

        @Override
        public List<AiChatMessage> listRecentMessages(long sessionId, int limit) {
            return List.of();
        }

        @Override
        public synchronized SessionClaim claimSession(long sessionId, String clientMessageId,
                                                      LocalDateTime leaseUntil, int retentionDays) {
            if (clientMessageId.equals(completedClientMessageId)) {
                return SessionClaim.completed(completedMessage);
            }
            if (activeRequestId != null) {
                busyClaimObserved.countDown();
                return SessionClaim.busy();
            }
            activeRequestId = clientMessageId;
            requestToken++;
            return SessionClaim.acquired(version, requestToken);
        }

        @Override
        public synchronized Optional<AiChatMessage> saveRoundIfCurrent(
                long sessionId, String clientMessageId, long sessionVersion, long claimedToken,
                ChatMode mode, String userContent, String assistantContent, String toolEvents,
                int retentionDays, MessageMetadata metadata) {
            if (version != sessionVersion || requestToken != claimedToken
                    || !clientMessageId.equals(activeRequestId)) {
                return Optional.empty();
            }
            activeRequestId = null;
            savedRounds.incrementAndGet();
            completedClientMessageId = clientMessageId;
            completedMessage = new AiChatMessage(messageIds.incrementAndGet(), sessionId, "assistant", mode.value(),
                    assistantContent, toolEvents, false, metadata.traceId(), metadata.modelName(),
                    metadata.promptVersion(), metadata.latencyMs(), metadata.promptTokens(),
                    metadata.completionTokens(), LocalDateTime.now());
            return Optional.of(completedMessage);
        }

        @Override
        public synchronized void releaseSessionClaim(long sessionId, String clientMessageId, long claimedToken) {
            if (requestToken == claimedToken && clientMessageId.equals(activeRequestId)) {
                activeRequestId = null;
                claimReleased.countDown();
            }
        }

        @Override
        public synchronized void clearMessages(long sessionId, int retentionDays) {
            version++;
            requestToken++;
            activeRequestId = null;
            completedClientMessageId = null;
            completedMessage = null;
        }
    }
}

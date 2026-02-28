package com.qwerlty.myojbackendaiservice.chat.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.chat.client.QuestionContextClient;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatSendRequest;
import com.qwerlty.myojbackendaiservice.chat.model.QuestionContext;
import com.qwerlty.myojbackendaiservice.chat.repository.AiChatRepository;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.observability.AiMetrics;
import com.qwerlty.myojbackendaiservice.sandbox.SignedCodeSandboxClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TutorToolServiceConcurrencyTest {

    @Test
    void concurrentCallsCannotOvershootDailyLimit() throws Exception {
        FakeRepository repository = new FakeRepository();
        TutorToolService service = new TutorToolService(repository, new EmptyQuestionClient(),
                (SignedCodeSandboxClient) null, new AiAgentProperties(), new ObjectMapper(),
                new AiMetrics(new SimpleMeterRegistry()));
        QuestionContext question = new QuestionContext(
                9L, "array", "content", "[]", "[]", "{}", 1);
        AiChatSendRequest request = new AiChatSendRequest(
                "quota-test", 9L, "agent", "cases", "java", null, null, List.of());
        TutorToolContext context = new TutorToolContext(7L, 11L, question, request);

        ExecutorService callers = Executors.newFixedThreadPool(2);
        TutorToolResult first;
        TutorToolResult second;
        try {
            Future<TutorToolResult> firstFuture = callers.submit(
                    () -> service.execute("testcase_generator", "{}", context));
            Future<TutorToolResult> secondFuture = callers.submit(
                    () -> service.execute("testcase_generator", "{}", context));
            first = firstFuture.get(3, TimeUnit.SECONDS);
            second = secondFuture.get(3, TimeUnit.SECONDS);
        } finally {
            callers.shutdownNow();
        }

        long successes = List.of(first, second).stream()
                .filter(result -> "done".equals(result.event().status()))
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(repository.successfulCalls).hasValue(1);
    }

    private static final class FakeRepository extends AiChatRepository {

        private final CyclicBarrier simultaneousAcquires = new CyclicBarrier(2);
        private final AtomicBoolean quotaReserved = new AtomicBoolean();
        private final AtomicInteger successfulCalls = new AtomicInteger();

        private FakeRepository() {
            super(new JdbcTemplate());
        }

        @Override
        public ToolPolicy findToolPolicy(String toolName) {
            return new ToolPolicy(true, 1);
        }

        @Override
        public boolean tryAcquireToolQuota(long userId, String toolName, int dailyLimit) {
            try {
                simultaneousAcquires.await(2, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
            return quotaReserved.compareAndSet(false, true);
        }

        @Override
        public void saveToolCall(long userId, long sessionId, String toolName,
                                 boolean success, String summary) {
            if (success) {
                successfulCalls.incrementAndGet();
            }
        }
    }

    private static final class EmptyQuestionClient implements QuestionContextClient {

        @Override
        public QuestionContext getQuestion(long questionId) {
            return null;
        }

        @Override
        public List<com.qwerlty.myojbackendaiservice.chat.model.SubmissionContext> listSubmissions(
                com.qwerlty.myojbackendaiservice.chat.model.SubmissionQuery query) {
            return List.of();
        }
    }
}

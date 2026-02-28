package com.qwerlty.myojbackendaiservice.authoring.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.authoring.api.ProblemDraftRequirements;
import com.qwerlty.myojbackendaiservice.authoring.client.AuthoringQuestionPublisher;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringProblemDraft;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringStage;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringTask;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringTaskStatus;
import com.qwerlty.myojbackendaiservice.authoring.repository.AuthoringTaskRepository;
import com.qwerlty.myojbackendaiservice.authoring.repository.AuthoringTraceRecorder;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.dto.JudgeCase;
import com.qwerlty.myojbackendaiservice.observability.AiMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bsc.langgraph4j.checkpoint.RedisSaver;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class QuestionAuthoringGraphRedisCheckpointTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @Test
    void resumesFromRedisCheckpointWithoutRepeatingCompletedGenerateNode() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AiAgentProperties properties = new AiAgentProperties();
        AuthoringTaskRepository repository = mock(AuthoringTaskRepository.class);
        AuthoringDraftModel model = mock(AuthoringDraftModel.class);
        AuthoringSandboxVerifier sandboxVerifier = mock(AuthoringSandboxVerifier.class);
        AuthoringQuestionPublisher questionPublisher = mock(AuthoringQuestionPublisher.class);
        AuthoringDraftValidator crashingValidator = mock(AuthoringDraftValidator.class);
        AuthoringProblemDraft draft = validDraft();
        AuthoringTask task = task(objectMapper);
        AiMetrics metrics = new AiMetrics(new SimpleMeterRegistry());
        AuthoringTraceRecorder trace = mock(AuthoringTraceRecorder.class);

        when(repository.isCancelRequested(anyLong())).thenReturn(false);
        when(model.generate(any())).thenReturn(
                new AuthoringDraftModel.GenerationOutcome(draft, "checkpoint-model", "authoring-v1"));
        when(crashingValidator.validate(any())).thenThrow(new IllegalStateException("simulated process interruption"));
        when(sandboxVerifier.verify(any())).thenReturn(
                new AuthoringSandboxVerifier.SandboxVerification(true, List.of()));

        RedissonClient firstClient = redisClient();
        try {
            RedisSaver saver = RedisSaver.builder().redissonClient(firstClient).build();
            QuestionAuthoringGraph interruptedGraph = new QuestionAuthoringGraph(
                    repository, model, crashingValidator, sandboxVerifier, questionPublisher, properties,
                    objectMapper, metrics, trace, saver);

            assertThatThrownBy(() -> interruptedGraph.execute(task))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("simulated process interruption");
            assertThat(interruptedGraph.hasCheckpoint(task.id())).isTrue();
            verify(model, times(1)).generate(any());
            verify(repository, never()).awaitReview(anyLong(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        } finally {
            firstClient.shutdown();
        }

        RedissonClient recoveredClient = redisClient();
        try {
            RedisSaver saver = RedisSaver.builder().redissonClient(recoveredClient).build();
            QuestionAuthoringGraph recoveredGraph = new QuestionAuthoringGraph(
                    repository, model, new AuthoringDraftValidator(), sandboxVerifier, questionPublisher, properties,
                    objectMapper, metrics, trace, saver);

            assertThat(recoveredGraph.hasCheckpoint(task.id())).isTrue();
            recoveredGraph.execute(task);
            verify(model, times(1)).generate(any());
            verify(repository, times(1)).awaitReview(anyLong(), anyString(), org.mockito.ArgumentMatchers.eq(0));
            saver.cleanupAll();
        } finally {
            recoveredClient.shutdown();
        }
    }

    @Test
    void resumesHumanApprovalFromRedisInANewGraphInstanceAndPublishesOnce() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AiAgentProperties properties = new AiAgentProperties();
        AuthoringTaskRepository repository = mock(AuthoringTaskRepository.class);
        AuthoringDraftModel model = mock(AuthoringDraftModel.class);
        AuthoringSandboxVerifier sandboxVerifier = mock(AuthoringSandboxVerifier.class);
        AuthoringQuestionPublisher publisher = mock(AuthoringQuestionPublisher.class);
        AuthoringTraceRecorder trace = mock(AuthoringTraceRecorder.class);
        AuthoringProblemDraft draft = validDraft();
        AuthoringTask task = task(objectMapper, 9002L);
        AiMetrics metrics = new AiMetrics(new SimpleMeterRegistry());

        when(repository.isCancelRequested(anyLong())).thenReturn(false);
        when(repository.markPublished(9002L, 99002L)).thenReturn(true);
        when(model.generate(any())).thenReturn(
                new AuthoringDraftModel.GenerationOutcome(draft, "checkpoint-model", "authoring-v1"));
        when(sandboxVerifier.verify(any())).thenReturn(
                new AuthoringSandboxVerifier.SandboxVerification(true, List.of()));
        when(publisher.publish(anyLong(), anyLong(), anyString(), any())).thenReturn(99002L);

        RedissonClient firstClient = redisClient();
        try {
            QuestionAuthoringGraph graph = new QuestionAuthoringGraph(
                    repository, model, new AuthoringDraftValidator(), sandboxVerifier, publisher, properties,
                    objectMapper, metrics, trace,
                    RedisSaver.builder().redissonClient(firstClient).build());
            graph.execute(task);
            verify(publisher, never()).publish(anyLong(), anyLong(), anyString(), any());
        } finally {
            firstClient.shutdown();
        }

        RedissonClient recoveredClient = redisClient();
        try {
            RedisSaver saver = RedisSaver.builder().redissonClient(recoveredClient).build();
            QuestionAuthoringGraph recovered = new QuestionAuthoringGraph(
                    repository, model, new AuthoringDraftValidator(), sandboxVerifier, publisher, properties,
                    objectMapper, metrics, trace, saver);

            recovered.resumeReview(reviewedTask(task, objectMapper.writeValueAsString(draft)));

            verify(publisher, times(1)).publish(
                    org.mockito.ArgumentMatchers.eq(9002L), org.mockito.ArgumentMatchers.eq(7L),
                    anyString(), org.mockito.ArgumentMatchers.eq(draft));
            verify(repository).markPublished(9002L, 99002L);
            saver.cleanupAll();
        } finally {
            recoveredClient.shutdown();
        }
    }

    private static RedissonClient redisClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        return Redisson.create(config);
    }

    private static AuthoringTask task(ObjectMapper objectMapper) throws Exception {
        return task(objectMapper, 9001L);
    }

    private static AuthoringTask task(ObjectMapper objectMapper, long taskId) throws Exception {
        String request = objectMapper.writeValueAsString(new ProblemDraftRequirements(
                "数组求和", 0, List.of("数组"), List.of("模拟"), "生成可验证题目"));
        LocalDateTime now = LocalDateTime.now();
        return new AuthoringTask(taskId, 7L, null, "redis-checkpoint", "PROBLEM_DRAFT", request, null,
                AuthoringTaskStatus.RUNNING, AuthoringStage.QUEUED, 1, 0, false,
                null, null, null, "authoring-v1", "authoring-v2-hitl", now, null, now, now);
    }

    private static AuthoringTask reviewedTask(AuthoringTask source, String draftJson) {
        LocalDateTime now = LocalDateTime.now();
        return new AuthoringTask(
                source.id(), source.userId(), source.sourceTaskId(), source.idempotencyKey(), source.taskType(),
                source.requestJson(), source.resultJson(), AuthoringTaskStatus.RUNNING,
                AuthoringStage.PUBLISHING, 96, source.repairCount(), false,
                null, null, source.modelName(), source.promptVersion(), source.graphVersion(),
                "APPROVE", draftJson, 7L, "人工复核通过", null, now,
                source.startedTime(), null, source.createTime(), now
        );
    }

    private static AuthoringProblemDraft validDraft() {
        List<JudgeCase> cases = new ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            cases.add(new JudgeCase(index + "\n", index + "\n"));
        }
        String description = "给定若干整数，按照题目规定完成计算并输出唯一结果。".repeat(8);
        return new AuthoringProblemDraft(
                "数组求和", 0, description, List.of("数组"), description,
                "public class Main { public static void main(String[] args) { } }",
                cases, AuthoringProblemDraft.JudgeConfig.defaults());
    }
}

package com.qwerlty.myojbackendaiservice.authoring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.authoring.api.CreateAuthoringTaskRequest;
import com.qwerlty.myojbackendaiservice.authoring.api.ProblemDraftRequirements;
import com.qwerlty.myojbackendaiservice.authoring.graph.QuestionAuthoringGraph;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringStage;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringTask;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringTaskStatus;
import com.qwerlty.myojbackendaiservice.authoring.repository.AuthoringTaskRepository;
import com.qwerlty.myojbackendaiservice.common.ApiException;
import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.observability.AiMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthoringTaskServiceTest {

    private AuthoringTaskRepository repository;
    private ThreadPoolTaskExecutor executor;
    private AuthoringTaskService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuthoringTaskRepository.class);
        executor = mock(ThreadPoolTaskExecutor.class);
        service = new AuthoringTaskService(repository, mock(QuestionAuthoringGraph.class),
                new ObjectMapper().findAndRegisterModules(), executor, new AiAgentProperties(),
                new AiMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void repeatedIdempotentCreateReturnsOriginalTaskAndSubmitsItOnlyOnce() {
        AuthoringTask task = task(61, 7, AuthoringTaskStatus.PENDING, null);
        when(repository.create(anyLong(), any(), any(), any(), any(), any())).thenReturn(task);
        CreateAuthoringTaskRequest request = new CreateAuthoringTaskRequest(
                new ProblemDraftRequirements("前缀和", 1, List.of("数组"), List.of(), null));

        assertThat(service.create(7L, request, "same-key").taskId()).isEqualTo("61");
        assertThat(service.create(7L, request, "same-key").taskId()).isEqualTo("61");

        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void hidesTaskExistenceFromAnotherUser() {
        when(repository.findById(61L)).thenReturn(Optional.of(
                task(61, 8, AuthoringTaskStatus.FAILED, null)));

        assertThatThrownBy(() -> service.get(7L, 61L))
                .isInstanceOf(ApiException.class)
                .hasMessage("任务不存在");
    }

    @Test
    void manualRetryCreatesANewAuditedTaskWithSourceId() {
        AuthoringTask source = task(61, 7, AuthoringTaskStatus.FAILED, null);
        AuthoringTask retry = task(62, 7, AuthoringTaskStatus.PENDING, 61L);
        when(repository.findById(61L)).thenReturn(Optional.of(source));
        when(repository.create(anyLong(), any(), any(), any(), any(), any())).thenReturn(retry);

        assertThat(service.retry(7L, 61L).sourceTaskId()).isEqualTo("61");
        verify(repository).create(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(61L), any(), any(), any(), any());
    }

    private static AuthoringTask task(long id, long userId, AuthoringTaskStatus status, Long sourceTaskId) {
        LocalDateTime now = LocalDateTime.now();
        return new AuthoringTask(id, userId, sourceTaskId, "idem-" + id, "PROBLEM_DRAFT",
                "{\"topic\":\"prefix sum\"}", null, status, AuthoringStage.QUEUED,
                0, 0, false, null, null, null, "authoring-v1", "authoring-v1",
                null, null, now, now);
    }
}

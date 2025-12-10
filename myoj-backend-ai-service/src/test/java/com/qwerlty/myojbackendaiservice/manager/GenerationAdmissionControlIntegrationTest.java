package com.qwerlty.myojbackendaiservice.manager;

import com.qwerlty.myojbackendaiservice.exception.BusinessException;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class GenerationAdmissionControlIntegrationTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private GenerationAdmissionControl admission;
    private AiProblemGenerationTaskMapper taskMapper;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        taskMapper = mock(AiProblemGenerationTaskMapper.class);
        admission = new GenerationAdmissionControl(redis, taskMapper, 10, 2, 3, 2, 1,
                10_000L, 1_000_000L, 100_000L);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void quotaPendingAndRunningSlotsAreReservedAndRefundedAtomically() {
        var first = admission.reserve(7L, "user", AuthoringTaskType.PROBLEM_DRAFT, "request-a");
        var second = admission.reserve(7L, "user", AuthoringTaskType.PROBLEM_DRAFT, "request-b");

        assertThat(admission.quota(7L, "user").getUsed()).isEqualTo(6);
        assertThatThrownBy(() -> admission.reserve(
                7L, "user", AuthoringTaskType.PROBLEM_DRAFT, "request-c"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(42911);

        AiProblemGenerationTask firstTask = task(1L, 7L, first);
        AiProblemGenerationTask secondTask = task(2L, 7L, second);
        assertThat(admission.tryStart(firstTask)).isTrue();
        assertThat(admission.tryStart(secondTask)).isFalse();

        assertThat(admission.settle(firstTask, true)).isTrue();
        assertThat(admission.quota(7L, "user").getUsed()).isEqualTo(3);
        assertThat(admission.tryStart(secondTask)).isTrue();
    }

    @Test
    void publicLaneCapacityDoesNotConsumeTheReservedReviewLane() {
        admission.reserve(1L, "user", AuthoringTaskType.PROBLEM_DRAFT, "public-a");
        admission.reserve(2L, "user", AuthoringTaskType.PROBLEM_DRAFT, "public-b");

        assertThatThrownBy(() -> admission.reserve(
                3L, "user", AuthoringTaskType.PROBLEM_DRAFT, "public-c"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(42912);

        assertThat(admission.reserve(99L, "admin", AuthoringTaskType.QUALITY_REVIEW, "review-a"))
                .isNotNull();
    }

    @Test
    void restoresQuotaAndSlotsFromDatabaseAfterRedisRestart() {
        var reservation = admission.reserve(7L, "user", AuthoringTaskType.PROBLEM_DRAFT, "restore-a");
        AiProblemGenerationTask task = task(11L, 7L, reservation);
        task.setQuotaCost(3);
        task.setStatus(0);

        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        when(taskMapper.countActiveForAdmission()).thenReturn(1L);
        when(taskMapper.countActiveForAdmissionByLane("PUBLIC_AUTHORING")).thenReturn(1L);
        when(taskMapper.countPendingForAdmission(7L)).thenReturn(1L);
        when(taskMapper.sumQuotaForAdmission(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.any(Date.class))).thenReturn(3L);

        assertThat(admission.tryStart(task)).isTrue();
        assertThat(admission.quota(7L, "user").getUsed()).isEqualTo(3);

        task.setStatus(5);
        assertThat(admission.settle(task, true)).isTrue();
        assertThat(admission.quota(7L, "user").getUsed()).isZero();
    }

    private AiProblemGenerationTask task(Long id, Long userId,
                                          GenerationAdmissionControl.Reservation reservation) {
        AiProblemGenerationTask task = new AiProblemGenerationTask();
        task.setId(id);
        task.setUserId(userId);
        task.setRequestKey(reservation.requestKey());
        task.setMode(AuthoringTaskType.PROBLEM_DRAFT.name());
        task.setLane(reservation.lane().name());
        task.setQuotaDate(Date.valueOf(reservation.quotaDate()));
        return task;
    }
}

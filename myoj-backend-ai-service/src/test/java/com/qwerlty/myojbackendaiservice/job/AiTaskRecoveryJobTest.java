package com.qwerlty.myojbackendaiservice.job;

import com.qwerlty.myojbackendaiservice.mapper.AiFeedbackTaskMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiFeedbackTask;
import com.qwerlty.myojbackendaiservice.model.enums.AiFeedbackStatusEnum;
import com.qwerlty.myojbackendaiservice.queue.AiFeedbackStreamConsumer;
import com.qwerlty.myojbackendaiservice.queue.AiFeedbackStreamManager;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTaskRecoveryJobTest {

    @Test
    void staleRunningTaskIsRecoveredAndPendingMessagesAreReclaimed() {
        AiFeedbackTaskMapper mapper = mock(AiFeedbackTaskMapper.class);
        AiFeedbackStreamManager stream = mock(AiFeedbackStreamManager.class);
        AiFeedbackStreamConsumer consumer = mock(AiFeedbackStreamConsumer.class);
        AiFeedbackTask task = new AiFeedbackTask();
        task.setId(31L);
        task.setStatus(AiFeedbackStatusEnum.RUNNING.getValue());
        task.setAttemptCount(1);
        MapRecord<String, String, String> record = record("1-0", "31");
        when(mapper.selectById(31L)).thenReturn(task);
        when(mapper.markExecutionRetry(31L, "TASK_RECOVERED", "检测到执行实例中断，任务已重新排队"))
                .thenReturn(1);
        when(stream.claimStale("test-recovery", Duration.ofSeconds(120), 20)).thenReturn(List.of(record));

        new AiTaskRecoveryJob(mapper, stream, consumer, 3, 120_000L, 20, "test").recover();

        verify(mapper).markExecutionRetry(eq(31L), eq("TASK_RECOVERED"), any());
        verify(consumer).consume(record);
    }

    @Test
    void exhaustedStaleTaskBecomesTimeout() {
        AiFeedbackTaskMapper mapper = mock(AiFeedbackTaskMapper.class);
        AiFeedbackStreamManager stream = mock(AiFeedbackStreamManager.class);
        AiFeedbackTask task = new AiFeedbackTask();
        task.setId(32L);
        task.setStatus(AiFeedbackStatusEnum.RUNNING.getValue());
        task.setAttemptCount(3);
        MapRecord<String, String, String> record = record("2-0", "32");
        when(mapper.selectById(32L)).thenReturn(task);
        when(mapper.markExecutionTerminal(32L, AiFeedbackStatusEnum.TIMEOUT.getValue(),
                "TASK_TIMEOUT", "AI 分析执行超时", 120_000L)).thenReturn(1);
        when(stream.claimStale(any(), any(), eq(20))).thenReturn(List.of(record));
        AiFeedbackStreamConsumer consumer = mock(AiFeedbackStreamConsumer.class);

        new AiTaskRecoveryJob(mapper, stream, consumer,
                3, 120_000L, 20, "test").recover();

        verify(mapper).markExecutionTerminal(
                32L,
                AiFeedbackStatusEnum.TIMEOUT.getValue(),
                "TASK_TIMEOUT",
                "AI 分析执行超时",
                120_000L);
        verify(stream).acknowledgeAndDelete(RecordId.of("2-0"));
        verify(consumer, never()).consume(record);
    }

    private MapRecord<String, String, String> record(String id, String taskId) {
        return MapRecord.create("myoj:ai:feedback:stream", Map.of("taskId", taskId))
                .withId(RecordId.of(id));
    }
}

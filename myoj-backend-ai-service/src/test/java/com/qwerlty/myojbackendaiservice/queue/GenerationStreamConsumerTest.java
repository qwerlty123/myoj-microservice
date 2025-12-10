package com.qwerlty.myojbackendaiservice.queue;

import com.qwerlty.myojbackendaiservice.mapper.AiProblemGenerationTaskMapper;
import com.qwerlty.myojbackendaiservice.manager.GenerationAdmissionControl;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import com.qwerlty.myojbackendaiservice.model.enums.GenerationLane;
import com.qwerlty.myojbackendaiservice.service.GenerationTaskService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationStreamConsumerTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void keepsEveryListenerActiveAfterTransientRedisPollingErrors() {
        Fixture fixture = fixture();

        fixture.consumer.subscribe();

        verify(fixture.stream).ensureGroup();
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(StreamReadRequest.class);
        verify(fixture.container, times(3)).register(requestCaptor.capture(), any());
        assertThat(requestCaptor.getAllValues()).allSatisfy(request ->
                assertThat(request.getCancelSubscriptionOnError()
                        .test(new IllegalStateException("redis timeout"))).isFalse());
        verify(fixture.container).start();
    }

    @Test
    void duplicateDeliveryIsAcknowledgedWithoutAnotherGeneration() {
        Fixture fixture = fixture();
        when(fixture.mapper.selectById(51L)).thenReturn(task(51L));
        when(fixture.mapper.claimForExecution(51L)).thenReturn(0);

        fixture.consumer.consume(record("1-0", "51"));

        verify(fixture.service, never()).execute(51L);
        verify(fixture.stream).acknowledgeAndDelete(GenerationLane.PUBLIC_AUTHORING, RecordId.of("1-0"));
    }

    @Test
    void claimedTaskExecutesBeforeTheMessageIsDeleted() {
        Fixture fixture = fixture();
        when(fixture.mapper.selectById(52L)).thenReturn(task(52L));
        when(fixture.mapper.claimForExecution(52L)).thenReturn(1);

        fixture.consumer.consume(record("2-0", "52"));

        verify(fixture.service).execute(52L);
        verify(fixture.stream).acknowledgeAndDelete(GenerationLane.PUBLIC_AUTHORING, RecordId.of("2-0"));
    }

    @Test
    void unexpectedWorkerCrashLeavesMessageForRecovery() {
        Fixture fixture = fixture();
        when(fixture.mapper.selectById(53L)).thenReturn(task(53L));
        when(fixture.mapper.claimForExecution(53L)).thenReturn(1);
        doThrow(new IllegalStateException("crash")).when(fixture.service).execute(53L);

        fixture.consumer.consume(record("3-0", "53"));

        verify(fixture.stream, never()).acknowledgeAndDelete(GenerationLane.PUBLIC_AUTHORING, RecordId.of("3-0"));
    }

    private Fixture fixture() {
        AiProblemGenerationTaskMapper mapper = mock(AiProblemGenerationTaskMapper.class);
        GenerationTaskService service = mock(GenerationTaskService.class);
        GenerationStreamManager stream = mock(GenerationStreamManager.class);
        when(stream.getGroup(any())).thenReturn("myoj-ai-generation");
        when(stream.getStreamKey(any())).thenReturn("myoj:ai:generation:stream");
        GenerationAdmissionControl admission = mock(GenerationAdmissionControl.class);
        when(admission.tryStart(any())).thenReturn(true);
        @SuppressWarnings("unchecked")
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                mock(StreamMessageListenerContainer.class);
        GenerationStreamConsumer consumer = new GenerationStreamConsumer(
                mapper, service, stream, container, new SimpleMeterRegistry(), admission, "test", 2, 1);
        return new Fixture(mapper, service, stream, container, consumer);
    }

    private MapRecord<String, String, String> record(String id, String taskId) {
        return MapRecord.create("myoj:ai:generation:stream", Map.of("taskId", taskId))
                .withId(RecordId.of(id));
    }

    private AiProblemGenerationTask task(Long id) {
        AiProblemGenerationTask task = new AiProblemGenerationTask();
        task.setId(id);
        task.setUserId(7L);
        task.setRequestKey("request-" + id);
        task.setLane(GenerationLane.PUBLIC_AUTHORING.name());
        return task;
    }

    private record Fixture(AiProblemGenerationTaskMapper mapper,
                           GenerationTaskService service,
                           GenerationStreamManager stream,
                           StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
                           GenerationStreamConsumer consumer) {
    }
}

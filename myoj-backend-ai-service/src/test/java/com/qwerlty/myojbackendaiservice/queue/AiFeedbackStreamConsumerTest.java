package com.qwerlty.myojbackendaiservice.queue;

import com.qwerlty.myojbackendaiservice.mapper.AiFeedbackTaskMapper;
import com.qwerlty.myojbackendaiservice.service.AiFeedbackService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.util.Map;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiFeedbackStreamConsumerTest {

    @Test
    void subscribeStartsTheListenerContainer() {
        Fixture fixture = fixture();

        fixture.consumer.subscribe();

        verify(fixture.stream).ensureGroup();
        verify(fixture.container, times(2)).receive(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(fixture.container).start();
    }

    @Test
    void duplicateMessageIsAcknowledgedWithoutModelExecution() {
        Fixture fixture = fixture();
        when(fixture.mapper.claimForExecution(51L)).thenReturn(0);

        fixture.consumer.consume(record("1-0", "51"));

        verify(fixture.service, never()).executeTask(51L);
        verify(fixture.stream).acknowledgeAndDelete(RecordId.of("1-0"));
    }

    @Test
    void claimedMessageExecutesThenAcknowledges() {
        Fixture fixture = fixture();
        when(fixture.mapper.claimForExecution(52L)).thenReturn(1);

        fixture.consumer.consume(record("2-0", "52"));

        verify(fixture.service).executeTask(52L);
        verify(fixture.stream).acknowledgeAndDelete(RecordId.of("2-0"));
    }

    @Test
    void unexpectedFailureRemainsPendingForRecovery() {
        Fixture fixture = fixture();
        when(fixture.mapper.claimForExecution(53L)).thenReturn(1);
        doThrow(new IllegalStateException("crash")).when(fixture.service).executeTask(53L);

        fixture.consumer.consume(record("3-0", "53"));

        verify(fixture.stream, never()).acknowledgeAndDelete(RecordId.of("3-0"));
    }

    private Fixture fixture() {
        AiFeedbackTaskMapper mapper = mock(AiFeedbackTaskMapper.class);
        AiFeedbackService service = mock(AiFeedbackService.class);
        AiFeedbackStreamManager stream = mock(AiFeedbackStreamManager.class);
        when(stream.getGroup()).thenReturn("myoj-ai-feedback");
        when(stream.getStreamKey()).thenReturn("myoj:ai:feedback:stream");
        @SuppressWarnings("unchecked")
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                mock(StreamMessageListenerContainer.class);
        AiFeedbackStreamConsumer consumer = new AiFeedbackStreamConsumer(
                mapper, service, stream, container, new SimpleMeterRegistry(), "test", 2);
        return new Fixture(mapper, service, stream, container, consumer);
    }

    private MapRecord<String, String, String> record(String id, String taskId) {
        return MapRecord.create("myoj:ai:feedback:stream", Map.of("taskId", taskId))
                .withId(RecordId.of(id));
    }

    private record Fixture(AiFeedbackTaskMapper mapper,
                           AiFeedbackService service,
                           AiFeedbackStreamManager stream,
                           StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
                           AiFeedbackStreamConsumer consumer) {
    }
}

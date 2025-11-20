package com.qwerlty.myojbackendaiservice.queue;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class AiFeedbackStreamManager {

    private final StringRedisTemplate redisTemplate;
    private final String streamKey;
    private final String group;
    private final long maxLength;

    public AiFeedbackStreamManager(
            StringRedisTemplate redisTemplate,
            @Value("${myoj.ai.stream.key:myoj:ai:feedback:stream}") String streamKey,
            @Value("${myoj.ai.stream.group:myoj-ai-feedback}") String group,
            @Value("${myoj.ai.stream.max-length:10000}") long maxLength) {
        this.redisTemplate = redisTemplate;
        this.streamKey = streamKey;
        this.group = group;
        this.maxLength = maxLength;
    }

    @PostConstruct
    @Scheduled(fixedDelayString = "${myoj.ai.stream.ensure-group-interval-ms:30000}")
    public void ensureGroup() {
        try {
            byte[] rawKey = streamKey.getBytes(StandardCharsets.UTF_8);
            redisTemplate.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(
                            rawKey, group, ReadOffset.from("0-0"), true));
        } catch (DataAccessException exception) {
            if (!isBusyGroup(exception)) {
                log.warn("Unable to ensure AI feedback Redis Stream group: {}", exception.getMessage());
            }
        }
    }

    public RecordId enqueue(Long taskId) {
        MapRecord<String, String, String> record = MapRecord.create(
                streamKey, Map.of("taskId", String.valueOf(taskId)));
        return streamOperations().add(
                record,
                RedisStreamCommands.XAddOptions.maxlen(maxLength).approximateTrimming(true));
    }

    public void acknowledgeAndDelete(RecordId recordId) {
        streamOperations().acknowledge(streamKey, group, recordId);
        streamOperations().delete(streamKey, recordId);
    }

    public List<MapRecord<String, String, String>> claimStale(
            String consumerName, Duration minIdleTime, int count) {
        PendingMessages pending = streamOperations()
                .pending(streamKey, group, Range.unbounded(), count);
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        RecordId[] ids = pending.stream()
                .filter(message -> isStale(message, minIdleTime))
                .map(PendingMessage::getId)
                .toArray(RecordId[]::new);
        if (ids.length == 0) {
            return List.of();
        }
        return streamOperations().claim(
                streamKey, group, consumerName, minIdleTime, ids);
    }

    private StreamOperations<String, String, String> streamOperations() {
        return redisTemplate.opsForStream();
    }

    public String getStreamKey() {
        return streamKey;
    }

    public String getGroup() {
        return group;
    }

    private boolean isStale(PendingMessage message, Duration minIdleTime) {
        return message.getElapsedTimeSinceLastDelivery().compareTo(minIdleTime) >= 0;
    }

    private boolean isBusyGroup(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

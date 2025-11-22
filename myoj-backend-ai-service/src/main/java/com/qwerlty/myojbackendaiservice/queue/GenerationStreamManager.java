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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GenerationStreamManager {
    private final StringRedisTemplate redisTemplate;
    private final String streamKey;
    private final String group;
    private final long maxLength;

    public GenerationStreamManager(
            StringRedisTemplate redisTemplate,
            @Value("${myoj.ai.generation.stream.key:myoj:ai:generation:stream}") String streamKey,
            @Value("${myoj.ai.generation.stream.group:myoj-ai-generation}") String group,
            @Value("${myoj.ai.generation.stream.max-length:5000}") long maxLength) {
        this.redisTemplate = redisTemplate;
        this.streamKey = streamKey;
        this.group = group;
        this.maxLength = maxLength;
    }

    @PostConstruct
    @Scheduled(fixedDelayString = "${myoj.ai.generation.stream.ensure-group-interval-ms:30000}")
    public void ensureGroup() {
        try {
            byte[] key = streamKey.getBytes(StandardCharsets.UTF_8);
            redisTemplate.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(
                            key, group, ReadOffset.from("0-0"), true));
        } catch (DataAccessException exception) {
            if (!isBusyGroup(exception)) {
                log.warn("[AI_GENERATION] unable to ensure stream group stream={} group={} errorType={}",
                        streamKey, group, exception.getClass().getSimpleName(), exception);
            }
        }
    }

    public RecordId enqueue(Long taskId) {
        MapRecord<String, String, String> record = MapRecord.create(
                streamKey, Map.of("taskId", String.valueOf(taskId)));
        RecordId id = operations().add(record,
                RedisStreamCommands.XAddOptions.maxlen(maxLength).approximateTrimming(true));
        if (id == null) {
            throw new IllegalStateException("Redis Stream 未返回消息 id");
        }
        log.info("[AI_GENERATION] task enqueued taskId={} recordId={} stream={} group={}",
                taskId, id.getValue(), streamKey, group);
        return id;
    }

    public void acknowledgeAndDelete(RecordId id) {
        operations().acknowledge(streamKey, group, id);
        operations().delete(streamKey, id);
        log.debug("[AI_GENERATION] stream record acknowledged recordId={} stream={} group={}",
                id.getValue(), streamKey, group);
    }

    public List<MapRecord<String, String, String>> claimStale(
            String consumerName, Duration minIdleTime, int count) {
        PendingMessages pending = operations().pending(streamKey, group, Range.unbounded(), count);
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        RecordId[] ids = pending.stream()
                .filter(message -> message.getElapsedTimeSinceLastDelivery().compareTo(minIdleTime) >= 0)
                .map(PendingMessage::getId)
                .toArray(RecordId[]::new);
        return ids.length == 0 ? List.of()
                : operations().claim(streamKey, group, consumerName, minIdleTime, ids);
    }

    private StreamOperations<String, String, String> operations() {
        return redisTemplate.opsForStream();
    }

    public String getStreamKey() {
        return streamKey;
    }

    public String getGroup() {
        return group;
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

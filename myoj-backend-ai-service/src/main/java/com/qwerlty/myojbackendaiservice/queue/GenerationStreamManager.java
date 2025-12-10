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
import com.qwerlty.myojbackendaiservice.model.enums.GenerationLane;

@Slf4j
@Component
public class GenerationStreamManager {
    private final StringRedisTemplate redisTemplate;
    private final String publicStreamKey;
    private final String reviewStreamKey;
    private final String publicGroup;
    private final String reviewGroup;
    private final long maxLength;

    public GenerationStreamManager(
            StringRedisTemplate redisTemplate,
            @Value("${myoj.ai.generation.stream.public-key:${myoj.ai.generation.stream.key:myoj:ai:generation:public}}") String publicStreamKey,
            @Value("${myoj.ai.generation.stream.review-key:myoj:ai:generation:review}") String reviewStreamKey,
            @Value("${myoj.ai.generation.stream.public-group:${myoj.ai.generation.stream.group:myoj-ai-generation-public}}") String publicGroup,
            @Value("${myoj.ai.generation.stream.review-group:myoj-ai-generation-review}") String reviewGroup,
            @Value("${myoj.ai.generation.stream.max-length:5000}") long maxLength) {
        this.redisTemplate = redisTemplate;
        this.publicStreamKey = publicStreamKey;
        this.reviewStreamKey = reviewStreamKey;
        this.publicGroup = publicGroup;
        this.reviewGroup = reviewGroup;
        this.maxLength = maxLength;
    }

    @PostConstruct
    @Scheduled(fixedDelayString = "${myoj.ai.generation.stream.ensure-group-interval-ms:30000}")
    public void ensureGroup() {
        ensureGroup(GenerationLane.PUBLIC_AUTHORING);
        ensureGroup(GenerationLane.ADMIN_REVIEW);
    }

    private void ensureGroup(GenerationLane lane) {
        String streamKey = getStreamKey(lane);
        String group = getGroup(lane);
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
        return enqueue(taskId, GenerationLane.PUBLIC_AUTHORING);
    }

    public RecordId enqueue(Long taskId, GenerationLane lane) {
        String streamKey = getStreamKey(lane);
        String group = getGroup(lane);
        MapRecord<String, String, String> record = MapRecord.create(
                streamKey, Map.of("taskId", String.valueOf(taskId), "lane", lane.name()));
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
        acknowledgeAndDelete(GenerationLane.PUBLIC_AUTHORING, id);
    }

    public void acknowledgeAndDelete(GenerationLane lane, RecordId id) {
        String streamKey = getStreamKey(lane);
        String group = getGroup(lane);
        operations().acknowledge(streamKey, group, id);
        operations().delete(streamKey, id);
        log.debug("[AI_GENERATION] stream record acknowledged recordId={} stream={} group={}",
                id.getValue(), streamKey, group);
    }

    public List<MapRecord<String, String, String>> claimStale(
            String consumerName, Duration minIdleTime, int count) {
        return claimStale(GenerationLane.PUBLIC_AUTHORING, consumerName, minIdleTime, count);
    }

    public List<MapRecord<String, String, String>> claimStale(
            GenerationLane lane, String consumerName, Duration minIdleTime, int count) {
        String streamKey = getStreamKey(lane);
        String group = getGroup(lane);
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
        return publicStreamKey;
    }

    public String getGroup() {
        return publicGroup;
    }

    public String getStreamKey(GenerationLane lane) {
        return lane == GenerationLane.ADMIN_REVIEW ? reviewStreamKey : publicStreamKey;
    }

    public String getGroup(GenerationLane lane) {
        return lane == GenerationLane.ADMIN_REVIEW ? reviewGroup : publicGroup;
    }

    public long streamLength(GenerationLane lane) {
        Long size = operations().size(getStreamKey(lane));
        return size == null ? 0L : size;
    }

    public long pendingCount(GenerationLane lane) {
        var summary = operations().pending(getStreamKey(lane), getGroup(lane));
        return summary == null ? 0L : summary.getTotalPendingMessages();
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

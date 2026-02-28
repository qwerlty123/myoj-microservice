package com.qwerlty.myojbackendaiservice.authoring.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerlty.myojbackendaiservice.authoring.api.AuthoringTraceEventView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class AuthoringTraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuthoringTraceRecorder.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuthoringTraceRecorder(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public String newRunId() {
        return UUID.randomUUID().toString();
    }

    public Instant started() {
        return Instant.now();
    }

    public long elapsedMs(Instant started) {
        return Math.max(0, Duration.between(started, Instant.now()).toMillis());
    }

    public void record(long taskId,
                       String graphVersion,
                       String runId,
                       String eventType,
                       String nodeId,
                       String fromNode,
                       String toNode,
                       String outcome,
                       Long durationMs,
                       Long actorId,
                       Map<String, ?> details) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO ai_authoring_trace_event
                    (traceId, runId, taskId, graphThreadId, graphVersion, eventType,
                     nodeId, fromNode, toNode, outcome, durationMs, actorId, detailJson, createTime)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    traceId(taskId), runId, taskId, threadId(taskId), graphVersion, eventType,
                    nodeId, fromNode, toNode, outcome, durationMs, actorId,
                    writeDetails(details), LocalDateTime.now());
        } catch (DataAccessException exception) {
            // Trace loss must be visible without turning an otherwise idempotent workflow into a retry storm.
            log.error("Cannot persist authoring trace event task={} run={} type={}",
                    taskId, runId, eventType, exception);
        }
    }

    public List<AuthoringTraceEventView> list(long taskId) {
        return jdbcTemplate.query("""
                SELECT id, traceId, runId, graphThreadId, graphVersion,
                       eventType, nodeId, fromNode, toNode, outcome,
                       durationMs, actorId, detailJson, createTime
                FROM ai_authoring_trace_event
                WHERE taskId = ?
                ORDER BY id
                """, (rs, rowNum) -> new AuthoringTraceEventView(
                Long.toString(rs.getLong("id")),
                rs.getString("traceId"),
                rs.getString("runId"),
                rs.getString("graphThreadId"),
                rs.getString("graphVersion"),
                rs.getString("eventType"),
                rs.getString("nodeId"),
                rs.getString("fromNode"),
                rs.getString("toNode"),
                rs.getString("outcome"),
                nullableLong(rs.getObject("durationMs")),
                nullableLong(rs.getObject("actorId")) == null
                        ? null : Long.toString(rs.getLong("actorId")),
                rs.getString("detailJson"),
                toLocalDateTime(rs.getTimestamp("createTime"))
        ), taskId);
    }

    public String fingerprint(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算 trace 内容摘要", exception);
        }
    }

    public static String traceId(long taskId) {
        return "authoring-task-" + taskId;
    }

    public static String threadId(long taskId) {
        return "authoring-task-" + taskId;
    }

    private String writeDetails(Map<String, ?> details) {
        try {
            String value = objectMapper.writeValueAsString(details == null ? Map.of() : details);
            if (value.length() <= 2000) return value;
            return objectMapper.writeValueAsString(Map.of(
                    "truncated", true,
                    "detailHash", fingerprint(value)
            ));
        } catch (Exception exception) {
            return "{}";
        }
    }

    private static Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}

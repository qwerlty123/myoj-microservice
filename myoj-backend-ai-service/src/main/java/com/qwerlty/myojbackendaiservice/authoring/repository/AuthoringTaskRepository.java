package com.qwerlty.myojbackendaiservice.authoring.repository;

import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringStage;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringTask;
import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringTaskStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class AuthoringTaskRepository {

    private static final String COLUMNS = """
            id, userId, sourceTaskId, idempotencyKey, taskType, requestJson, resultJson,
            status, stage, progress, repairCount, cancelRequested, errorCode, lastError,
            modelName, promptVersion, graphVersion, startedTime, finishedTime, createTime, updateTime
            """;

    private final JdbcTemplate jdbcTemplate;

    public AuthoringTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AuthoringTask create(long userId,
                                Long sourceTaskId,
                                String idempotencyKey,
                                String requestJson,
                                String promptVersion,
                                String graphVersion) {
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ai_authoring_task
                        (userId, sourceTaskId, idempotencyKey, taskType, requestJson,
                         status, stage, progress, repairCount, cancelRequested,
                         promptVersion, graphVersion, createTime, updateTime, isDelete)
                        VALUES (?, ?, ?, 'PROBLEM_DRAFT', ?, 'PENDING', 'QUEUED', 0, 0, 0, ?, ?, ?, ?, 0)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, userId);
                if (sourceTaskId == null) statement.setNull(2, java.sql.Types.BIGINT);
                else statement.setLong(2, sourceTaskId);
                statement.setString(3, idempotencyKey);
                statement.setString(4, requestJson);
                statement.setString(5, promptVersion);
                statement.setString(6, graphVersion);
                statement.setTimestamp(7, Timestamp.valueOf(now));
                statement.setTimestamp(8, Timestamp.valueOf(now));
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException exception) {
            return findByUserAndIdempotencyKey(userId, idempotencyKey).orElseThrow(() -> exception);
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("AI 出题任务创建后没有返回主键");
        }
        return findById(key.longValue()).orElseThrow();
    }

    public Optional<AuthoringTask> findById(long taskId) {
        return jdbcTemplate.query("SELECT " + COLUMNS
                        + " FROM ai_authoring_task WHERE id = ? AND isDelete = 0 LIMIT 1",
                ROW_MAPPER, taskId).stream().findFirst();
    }

    public Optional<AuthoringTask> findByUserAndIdempotencyKey(long userId, String idempotencyKey) {
        return jdbcTemplate.query("SELECT " + COLUMNS
                        + " FROM ai_authoring_task WHERE userId = ? AND idempotencyKey = ? AND isDelete = 0 LIMIT 1",
                ROW_MAPPER, userId, idempotencyKey).stream().findFirst();
    }

    public List<AuthoringTask> listByUser(long userId, int offset, int limit) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM ai_authoring_task "
                        + "WHERE userId = ? AND taskType = 'PROBLEM_DRAFT' AND isDelete = 0 "
                        + "ORDER BY id DESC LIMIT ? OFFSET ?", ROW_MAPPER, userId, limit, offset);
    }

    public long countByUser(long userId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ai_authoring_task
                WHERE userId = ? AND taskType = 'PROBLEM_DRAFT' AND isDelete = 0
                """, Long.class, userId);
        return count == null ? 0 : count;
    }

    public List<AuthoringTask> listRecoverable(LocalDateTime staleBefore, int limit) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM ai_authoring_task "
                        + "WHERE isDelete = 0 AND cancelRequested = 0 AND (status = 'PENDING' "
                        + "OR (status = 'RUNNING' AND updateTime < ?)) ORDER BY id LIMIT ?",
                ROW_MAPPER, staleBefore, limit);
    }

    public void markRunning(long taskId, AuthoringStage stage, int progress) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                UPDATE ai_authoring_task
                SET status = 'RUNNING', stage = ?, progress = ?, startedTime = COALESCE(startedTime, ?),
                    errorCode = NULL, lastError = NULL, updateTime = ?
                WHERE id = ? AND status IN ('PENDING', 'RUNNING') AND cancelRequested = 0 AND isDelete = 0
                """, stage.name(), progress, now, now, taskId);
    }

    public void updateStage(long taskId, AuthoringStage stage, int progress, int repairCount) {
        jdbcTemplate.update("""
                UPDATE ai_authoring_task
                SET stage = ?, progress = ?, repairCount = ?, updateTime = ?
                WHERE id = ? AND status = 'RUNNING' AND cancelRequested = 0 AND isDelete = 0
                """, stage.name(), progress, repairCount, LocalDateTime.now(), taskId);
    }

    public void updateModel(long taskId, String modelName, String promptVersion) {
        jdbcTemplate.update("""
                UPDATE ai_authoring_task SET modelName = ?, promptVersion = ?, updateTime = ?
                WHERE id = ? AND isDelete = 0
                """, modelName, promptVersion, LocalDateTime.now(), taskId);
    }

    public void complete(long taskId, String resultJson, int repairCount) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                UPDATE ai_authoring_task
                SET status = 'REVIEW_REQUIRED', stage = 'COMPLETED', progress = 100,
                    resultJson = ?, repairCount = ?, errorCode = NULL, lastError = NULL,
                    finishedTime = ?, updateTime = ?
                WHERE id = ? AND status = 'RUNNING' AND cancelRequested = 0 AND isDelete = 0
                """, resultJson, repairCount, now, now, taskId);
    }

    public void fail(long taskId, String errorCode, String lastError, int repairCount) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                UPDATE ai_authoring_task
                SET status = 'FAILED', stage = 'FAILED', progress = 100, errorCode = ?, lastError = ?,
                    repairCount = ?, finishedTime = ?, updateTime = ?
                WHERE id = ? AND status IN ('PENDING', 'RUNNING') AND isDelete = 0
                """, errorCode, truncate(lastError, 1000), repairCount, now, now, taskId);
    }

    public void requestCancel(long taskId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                UPDATE ai_authoring_task
                SET cancelRequested = 1,
                    stage = CASE WHEN status = 'PENDING' THEN 'CANCELLED' ELSE stage END,
                    finishedTime = CASE WHEN status = 'PENDING' THEN ? ELSE finishedTime END,
                    status = CASE WHEN status = 'PENDING' THEN 'CANCELLED' ELSE status END,
                    updateTime = ?
                WHERE id = ? AND status IN ('PENDING', 'RUNNING') AND isDelete = 0
                """, now, now, taskId);
    }

    public void markCancelled(long taskId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                UPDATE ai_authoring_task
                SET cancelRequested = 1, status = 'CANCELLED', stage = 'CANCELLED',
                    finishedTime = ?, updateTime = ?
                WHERE id = ? AND status IN ('PENDING', 'RUNNING') AND isDelete = 0
                """, now, now, taskId);
    }

    public boolean isCancelRequested(long taskId) {
        Boolean requested = jdbcTemplate.query("""
                        SELECT cancelRequested FROM ai_authoring_task WHERE id = ? AND isDelete = 0 LIMIT 1
                        """, (rs, rowNum) -> rs.getBoolean(1), taskId).stream().findFirst().orElse(true);
        return Boolean.TRUE.equals(requested);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private static final RowMapper<AuthoringTask> ROW_MAPPER = (rs, rowNum) -> new AuthoringTask(
            rs.getLong("id"),
            rs.getLong("userId"),
            nullableLong(rs.getObject("sourceTaskId")),
            rs.getString("idempotencyKey"),
            rs.getString("taskType"),
            rs.getString("requestJson"),
            rs.getString("resultJson"),
            AuthoringTaskStatus.valueOf(rs.getString("status")),
            AuthoringStage.valueOf(rs.getString("stage")),
            rs.getInt("progress"),
            rs.getInt("repairCount"),
            rs.getBoolean("cancelRequested"),
            rs.getString("errorCode"),
            rs.getString("lastError"),
            rs.getString("modelName"),
            rs.getString("promptVersion"),
            rs.getString("graphVersion"),
            toLocalDateTime(rs.getTimestamp("startedTime")),
            toLocalDateTime(rs.getTimestamp("finishedTime")),
            toLocalDateTime(rs.getTimestamp("createTime")),
            toLocalDateTime(rs.getTimestamp("updateTime"))
    );

    private static Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}

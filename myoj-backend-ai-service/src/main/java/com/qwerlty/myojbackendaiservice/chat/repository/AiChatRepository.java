package com.qwerlty.myojbackendaiservice.chat.repository;

import com.qwerlty.myojbackendaiservice.chat.model.AiChatMessage;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatSession;
import com.qwerlty.myojbackendaiservice.chat.model.ChatMode;
import com.qwerlty.myojbackendaiservice.chat.model.ChatSessionStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class AiChatRepository {

    private static final String SESSION_COLUMNS = """
            id, userId, questionId, mode, status, disableReason,
            lastMessageTime, expireTime, createTime, updateTime
            """;
    private static final String MESSAGE_COLUMNS = """
            id, sessionId, role, mode, content, toolEvents, violation, createTime
            """;

    private final JdbcTemplate jdbcTemplate;

    public AiChatRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AiChatSession getOrCreateSession(long userId, long questionId, int retentionDays) {
        Optional<AiChatSession> existing = findSession(userId, questionId);
        if (existing.isPresent()) {
            return existing.get();
        }
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ai_chat_session
                        (userId, questionId, mode, status, lastMessageTime, expireTime, createTime, updateTime, isDelete)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, userId);
                statement.setLong(2, questionId);
                statement.setString(3, ChatMode.NORMAL.value());
                statement.setInt(4, ChatSessionStatus.ACTIVE.value());
                statement.setTimestamp(5, Timestamp.valueOf(now));
                statement.setTimestamp(6, Timestamp.valueOf(now.plusDays(Math.max(retentionDays, 1))));
                statement.setTimestamp(7, Timestamp.valueOf(now));
                statement.setTimestamp(8, Timestamp.valueOf(now));
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException ignored) {
            return findSession(userId, questionId).orElseThrow();
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            return findSession(userId, questionId).orElseThrow();
        }
        return findSessionById(key.longValue()).orElseThrow();
    }

    public Optional<AiChatSession> findSession(long userId, long questionId) {
        return jdbcTemplate.query("SELECT " + SESSION_COLUMNS + " FROM ai_chat_session "
                        + "WHERE userId = ? AND questionId = ? AND isDelete = 0 LIMIT 1",
                SESSION_ROW_MAPPER, userId, questionId).stream().findFirst();
    }

    public Optional<AiChatSession> findSessionById(long sessionId) {
        return jdbcTemplate.query("SELECT " + SESSION_COLUMNS + " FROM ai_chat_session "
                        + "WHERE id = ? AND isDelete = 0 LIMIT 1", SESSION_ROW_MAPPER, sessionId)
                .stream().findFirst();
    }

    public List<AiChatMessage> listMessages(long sessionId) {
        return jdbcTemplate.query("SELECT " + MESSAGE_COLUMNS + " FROM ai_chat_message "
                + "WHERE sessionId = ? AND isDelete = 0 ORDER BY id", MESSAGE_ROW_MAPPER, sessionId);
    }

    public List<AiChatMessage> listRecentMessages(long sessionId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query("SELECT " + MESSAGE_COLUMNS + " FROM (SELECT " + MESSAGE_COLUMNS
                        + " FROM ai_chat_message WHERE sessionId = ? AND isDelete = 0 ORDER BY id DESC LIMIT ?) recent "
                        + "ORDER BY id", MESSAGE_ROW_MAPPER, sessionId, safeLimit);
    }

    public AiChatMessage saveMessage(long sessionId, String role, String mode, String content,
                                     String toolEvents, boolean violation) {
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO ai_chat_message
                    (sessionId, role, mode, content, toolEvents, violation, createTime, isDelete)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, sessionId);
            statement.setString(2, role);
            statement.setString(3, mode);
            statement.setString(4, content);
            statement.setString(5, toolEvents);
            statement.setBoolean(6, violation);
            statement.setTimestamp(7, Timestamp.valueOf(now));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("AI 消息写入后没有返回主键");
        }
        return new AiChatMessage(key.longValue(), sessionId, role, mode, content, toolEvents, violation, now);
    }

    @Transactional
    public void clearMessages(long sessionId, int retentionDays) {
        jdbcTemplate.update("UPDATE ai_chat_message SET isDelete = 1 WHERE sessionId = ? AND isDelete = 0", sessionId);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                UPDATE ai_chat_session
                SET mode = ?, status = ?, disableReason = NULL, lastMessageTime = ?, expireTime = ?, updateTime = ?
                WHERE id = ? AND isDelete = 0
                """, ChatMode.NORMAL.value(), ChatSessionStatus.ACTIVE.value(), now,
                now.plusDays(Math.max(retentionDays, 1)), now, sessionId);
    }

    @Transactional
    public AiChatMessage saveRound(long sessionId, ChatMode mode, String userContent,
                                   String assistantContent, String toolEvents, int retentionDays) {
        saveMessage(sessionId, "user", mode.value(), userContent, null, false);
        AiChatMessage assistant = saveMessage(
                sessionId, "assistant", mode.value(), assistantContent, toolEvents, false);
        touchSession(sessionId, mode, retentionDays);
        return assistant;
    }

    public void touchSession(long sessionId, ChatMode mode, int retentionDays) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                UPDATE ai_chat_session
                SET mode = ?, status = ?, disableReason = NULL, lastMessageTime = ?, expireTime = ?, updateTime = ?
                WHERE id = ? AND isDelete = 0
                """, mode.value(), ChatSessionStatus.ACTIVE.value(), now,
                now.plusDays(Math.max(retentionDays, 1)), now, sessionId);
    }

    public void updateSessionAccess(long sessionId, int status, String disableReason) {
        jdbcTemplate.update("""
                UPDATE ai_chat_session SET status = ?, disableReason = ?, updateTime = ?
                WHERE id = ? AND isDelete = 0
                """, status, disableReason, LocalDateTime.now(), sessionId);
    }

    public int archiveExpiredSessions() {
        return jdbcTemplate.update("""
                UPDATE ai_chat_session SET status = ?, updateTime = ?
                WHERE status = ? AND expireTime < ? AND isDelete = 0
                """, ChatSessionStatus.ARCHIVED.value(), LocalDateTime.now(),
                ChatSessionStatus.ACTIVE.value(), LocalDateTime.now());
    }

    public Optional<String> findActivePrompt(String scene) {
        return jdbcTemplate.query("""
                        SELECT promptContent FROM ai_prompt_config
                        WHERE scene = ? AND enabled = 1 AND isActive = 1 AND isDelete = 0
                        ORDER BY versionNo DESC, id DESC LIMIT 1
                        """, (rs, rowNum) -> rs.getString(1), scene).stream().findFirst();
    }

    public Optional<String> findActiveModelName() {
        return jdbcTemplate.query("""
                        SELECT modelName FROM ai_model_config
                        WHERE enabled = 1 AND isDefault = 1 AND isDelete = 0
                        ORDER BY id DESC LIMIT 1
                        """, (rs, rowNum) -> rs.getString(1)).stream().findFirst();
    }

    public List<DisableRule> listActiveDisableRules() {
        LocalDateTime now = LocalDateTime.now();
        return jdbcTemplate.query("""
                SELECT scopeType, scopeId, reason FROM ai_disable_rule
                WHERE enabled = 1 AND isDelete = 0
                  AND (startTime IS NULL OR startTime <= ?)
                  AND (endTime IS NULL OR endTime >= ?)
                ORDER BY id DESC
                """, (rs, rowNum) -> new DisableRule(
                rs.getString("scopeType"), nullableLong(rs.getObject("scopeId")), rs.getString("reason")), now, now);
    }

    public List<String> listSensitiveWords() {
        return jdbcTemplate.query("""
                SELECT word FROM ai_sensitive_word
                WHERE enabled = 1 AND isDelete = 0 ORDER BY id
                """, (rs, rowNum) -> rs.getString(1));
    }

    public void saveViolation(long userId, long sessionId, Long messageId, String type, String content) {
        jdbcTemplate.update("""
                INSERT INTO ai_violation_log
                (userId, sessionId, messageId, violationType, content, createTime, isDelete)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                """, userId, sessionId, messageId, type, content, LocalDateTime.now());
    }

    public ToolPolicy findToolPolicy(String toolName) {
        return jdbcTemplate.query("""
                        SELECT enabled, dailyLimit FROM ai_tool_config
                        WHERE toolName = ? AND isDelete = 0 LIMIT 1
                        """, (rs, rowNum) -> new ToolPolicy(rs.getBoolean("enabled"), rs.getInt("dailyLimit")), toolName)
                .stream().findFirst().orElse(new ToolPolicy(true, 30));
    }

    public int countToolCallsToday(long userId, String toolName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ai_tool_call_log
                WHERE userId = ? AND toolName = ? AND createTime >= ? AND isDelete = 0
                """, Integer.class, userId, toolName, LocalDate.now().atStartOfDay());
        return count == null ? 0 : count;
    }

    public void saveToolCall(long userId, long sessionId, String toolName, boolean success, String summary) {
        jdbcTemplate.update("""
                INSERT INTO ai_tool_call_log
                (userId, sessionId, toolName, success, resultSummary, createTime, isDelete)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                """, userId, sessionId, toolName, success, truncate(summary, 1000), LocalDateTime.now());
    }

    private static Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static final RowMapper<AiChatSession> SESSION_ROW_MAPPER = (rs, rowNum) -> new AiChatSession(
            rs.getLong("id"),
            rs.getLong("userId"),
            rs.getLong("questionId"),
            rs.getString("mode"),
            rs.getInt("status"),
            rs.getString("disableReason"),
            toLocalDateTime(rs.getTimestamp("lastMessageTime")),
            toLocalDateTime(rs.getTimestamp("expireTime")),
            toLocalDateTime(rs.getTimestamp("createTime")),
            toLocalDateTime(rs.getTimestamp("updateTime"))
    );

    private static final RowMapper<AiChatMessage> MESSAGE_ROW_MAPPER = (rs, rowNum) -> new AiChatMessage(
            rs.getLong("id"),
            rs.getLong("sessionId"),
            rs.getString("role"),
            rs.getString("mode"),
            rs.getString("content"),
            rs.getString("toolEvents"),
            rs.getBoolean("violation"),
            toLocalDateTime(rs.getTimestamp("createTime"))
    );

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    public record DisableRule(String scopeType, Long scopeId, String reason) {
    }

    public record ToolPolicy(boolean enabled, int dailyLimit) {
    }
}

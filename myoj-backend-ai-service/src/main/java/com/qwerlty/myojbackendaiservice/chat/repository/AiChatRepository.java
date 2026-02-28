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
            id, sessionId, role, mode, content, toolEvents, violation, traceId, modelName,
            promptVersion, latencyMs, promptTokens, completionTokens, createTime
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

    @Transactional
    public SessionClaim claimSession(long sessionId, String clientMessageId,
                                     LocalDateTime leaseUntil, int retentionDays) {
        LocalDateTime now = LocalDateTime.now();
        SessionCoordination coordination = jdbcTemplate.query("""
                        SELECT version, activeRequestId, activeRequestToken, activeRequestExpireTime
                        FROM ai_chat_session
                        WHERE id = ? AND isDelete = 0
                        FOR UPDATE
                        """, (rs, rowNum) -> new SessionCoordination(
                        rs.getLong("version"), rs.getString("activeRequestId"),
                        rs.getLong("activeRequestToken"),
                        toLocalDateTime(rs.getTimestamp("activeRequestExpireTime"))), sessionId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("AI 会话不存在"));

        Optional<AiChatMessage> completed = findCompletedMessage(sessionId, clientMessageId);
        if (completed.isPresent()) {
            return SessionClaim.completed(completed.get());
        }
        if (coordination.activeRequestId() != null
                && (coordination.activeRequestExpireTime() == null
                || coordination.activeRequestExpireTime().isAfter(now))) {
            return SessionClaim.busy();
        }

        long token = Math.incrementExact(coordination.activeRequestToken());
        jdbcTemplate.update("""
                UPDATE ai_chat_session
                SET activeRequestId = ?, activeRequestToken = ?, activeRequestExpireTime = ?,
                    expireTime = ?, updateTime = ?
                WHERE id = ? AND isDelete = 0
                """, clientMessageId, token, leaseUntil,
                now.plusDays(Math.max(retentionDays, 1)), now, sessionId);
        return SessionClaim.acquired(coordination.version(), token);
    }

    public Optional<AiChatMessage> findCompletedMessage(long sessionId, String clientMessageId) {
        return jdbcTemplate.query("SELECT " + MESSAGE_COLUMNS + " FROM ai_chat_message "
                        + "WHERE sessionId = ? AND clientMessageId = ? AND role = 'assistant' "
                        + "AND isDelete = 0 ORDER BY id DESC LIMIT 1",
                MESSAGE_ROW_MAPPER, sessionId, clientMessageId).stream().findFirst();
    }

    public AiChatMessage saveMessage(long sessionId, String role, String mode, String content,
                                     String toolEvents, boolean violation, MessageMetadata metadata) {
        return saveMessage(sessionId, null, role, mode, content, toolEvents, violation, metadata);
    }

    private AiChatMessage saveMessage(long sessionId, String clientMessageId,
                                      String role, String mode, String content,
                                      String toolEvents, boolean violation, MessageMetadata metadata) {
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO ai_chat_message
                    (sessionId, clientMessageId, role, mode, content, toolEvents, violation, traceId, modelName,
                     promptVersion, latencyMs, promptTokens, completionTokens, createTime, isDelete)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, sessionId);
            statement.setString(2, clientMessageId);
            statement.setString(3, role);
            statement.setString(4, mode);
            statement.setString(5, content);
            statement.setString(6, toolEvents);
            statement.setBoolean(7, violation);
            statement.setString(8, metadata == null ? null : metadata.traceId());
            statement.setString(9, metadata == null ? null : metadata.modelName());
            statement.setString(10, metadata == null ? null : metadata.promptVersion());
            statement.setObject(11, metadata == null ? null : metadata.latencyMs());
            statement.setObject(12, metadata == null ? null : metadata.promptTokens());
            statement.setObject(13, metadata == null ? null : metadata.completionTokens());
            statement.setTimestamp(14, Timestamp.valueOf(now));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("AI 消息写入后没有返回主键");
        }
        return new AiChatMessage(key.longValue(), sessionId, role, mode, content, toolEvents, violation,
                metadata == null ? null : metadata.traceId(),
                metadata == null ? null : metadata.modelName(),
                metadata == null ? null : metadata.promptVersion(),
                metadata == null ? null : metadata.latencyMs(),
                metadata == null ? null : metadata.promptTokens(),
                metadata == null ? null : metadata.completionTokens(), now);
    }

    @Transactional
    public void clearMessages(long sessionId, int retentionDays) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                UPDATE ai_chat_session
                SET mode = ?, status = ?, disableReason = NULL,
                    version = version + 1,
                    activeRequestId = NULL,
                    activeRequestToken = activeRequestToken + 1,
                    activeRequestExpireTime = NULL,
                    lastMessageTime = ?, expireTime = ?, updateTime = ?
                WHERE id = ? AND isDelete = 0
                """, ChatMode.NORMAL.value(), ChatSessionStatus.ACTIVE.value(), now,
                now.plusDays(Math.max(retentionDays, 1)), now, sessionId);
        jdbcTemplate.update("UPDATE ai_chat_message SET isDelete = 1 WHERE sessionId = ? AND isDelete = 0", sessionId);
    }

    @Transactional
    public Optional<AiChatMessage> saveRoundIfCurrent(long sessionId, String clientMessageId,
                                                      long sessionVersion, long requestToken,
                                                      ChatMode mode, String userContent,
                                                      String assistantContent, String toolEvents,
                                                      int retentionDays, MessageMetadata metadata) {
        LocalDateTime now = LocalDateTime.now();
        int claimed = jdbcTemplate.update("""
                UPDATE ai_chat_session
                SET mode = ?, status = ?, disableReason = NULL,
                    activeRequestId = NULL, activeRequestExpireTime = NULL,
                    lastMessageTime = ?, expireTime = ?, updateTime = ?
                WHERE id = ? AND version = ? AND activeRequestId = ?
                  AND activeRequestToken = ? AND isDelete = 0
                """, mode.value(), ChatSessionStatus.ACTIVE.value(), now,
                now.plusDays(Math.max(retentionDays, 1)), now, sessionId,
                sessionVersion, clientMessageId, requestToken);
        if (claimed != 1) {
            return Optional.empty();
        }
        MessageMetadata userMetadata = metadata == null ? null
                : new MessageMetadata(metadata.traceId(), null, null, null, null, null);
        saveMessage(sessionId, clientMessageId, "user", mode.value(), userContent, null, false, userMetadata);
        AiChatMessage assistant = saveMessage(
                sessionId, clientMessageId, "assistant", mode.value(), assistantContent, toolEvents, false, metadata);
        return Optional.of(assistant);
    }

    public void releaseSessionClaim(long sessionId, String clientMessageId, long requestToken) {
        jdbcTemplate.update("""
                UPDATE ai_chat_session
                SET activeRequestId = NULL, activeRequestExpireTime = NULL, updateTime = ?
                WHERE id = ? AND activeRequestId = ? AND activeRequestToken = ? AND isDelete = 0
                """, LocalDateTime.now(), sessionId, clientMessageId, requestToken);
    }

    public void updateSessionAccess(long sessionId, int status, String disableReason) {
        if (status == ChatSessionStatus.DISABLED.value()) {
            jdbcTemplate.update("""
                    UPDATE ai_chat_session
                    SET status = ?, disableReason = ?, activeRequestId = NULL,
                        activeRequestToken = activeRequestToken + 1,
                        activeRequestExpireTime = NULL, updateTime = ?
                    WHERE id = ? AND isDelete = 0
                    """, status, disableReason, LocalDateTime.now(), sessionId);
            return;
        }
        String idleClause = status == ChatSessionStatus.ARCHIVED.value()
                ? " AND activeRequestId IS NULL" : "";
        jdbcTemplate.update("""
                UPDATE ai_chat_session SET status = ?, disableReason = ?, updateTime = ?
                WHERE id = ? AND isDelete = 0
                """ + idleClause, status, disableReason, LocalDateTime.now(), sessionId);
    }

    public int archiveExpiredSessions() {
        return jdbcTemplate.update("""
                UPDATE ai_chat_session SET status = ?, updateTime = ?
                WHERE status = ? AND expireTime < ? AND activeRequestId IS NULL AND isDelete = 0
                """, ChatSessionStatus.ARCHIVED.value(), LocalDateTime.now(),
                ChatSessionStatus.ACTIVE.value(), LocalDateTime.now());
    }

    public Optional<String> findActivePrompt(String scene) {
        return findActivePromptDefinition(scene).map(PromptDefinition::content);
    }

    public Optional<PromptDefinition> findActivePromptDefinition(String scene) {
        return jdbcTemplate.query("""
                        SELECT versionNo, promptContent FROM ai_prompt_config
                        WHERE scene = ? AND enabled = 1 AND isActive = 1 AND isDelete = 0
                        ORDER BY versionNo DESC, id DESC LIMIT 1
                        """, (rs, rowNum) -> new PromptDefinition(
                        "db-v" + rs.getInt("versionNo"), rs.getString("promptContent")), scene)
                .stream().findFirst();
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

    @Transactional
    public boolean tryAcquireToolQuota(long userId, String toolName, int dailyLimit) {
        jdbcTemplate.update("""
                INSERT IGNORE INTO ai_tool_daily_quota
                (userId, toolName, usageDate, usedCount, createTime, updateTime)
                VALUES (?, ?, CURRENT_DATE(), 0, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP())
                """, userId, toolName);
        Integer used = jdbcTemplate.queryForObject("""
                SELECT usedCount FROM ai_tool_daily_quota
                WHERE userId = ? AND toolName = ? AND usageDate = CURRENT_DATE()
                FOR UPDATE
                """, Integer.class, userId, toolName);
        if (dailyLimit > 0 && used != null && used >= dailyLimit) {
            return false;
        }
        return jdbcTemplate.update("""
                UPDATE ai_tool_daily_quota
                SET usedCount = usedCount + 1, updateTime = CURRENT_TIMESTAMP()
                WHERE userId = ? AND toolName = ? AND usageDate = CURRENT_DATE()
                """, userId, toolName) == 1;
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
            rs.getString("traceId"),
            rs.getString("modelName"),
            rs.getString("promptVersion"),
            nullableLong(rs.getObject("latencyMs")),
            nullableInteger(rs.getObject("promptTokens")),
            nullableInteger(rs.getObject("completionTokens")),
            toLocalDateTime(rs.getTimestamp("createTime"))
    );

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    public record DisableRule(String scopeType, Long scopeId, String reason) {
    }

    public record ToolPolicy(boolean enabled, int dailyLimit) {
    }

    public record PromptDefinition(String version, String content) {
    }

    public record MessageMetadata(String traceId, String modelName, String promptVersion,
                                  Long latencyMs, Integer promptTokens, Integer completionTokens) {
    }

    public enum ClaimState {
        ACQUIRED,
        COMPLETED,
        BUSY
    }

    public record SessionClaim(ClaimState state, long sessionVersion, long requestToken,
                               AiChatMessage completedMessage) {

        public static SessionClaim acquired(long sessionVersion, long requestToken) {
            return new SessionClaim(ClaimState.ACQUIRED, sessionVersion, requestToken, null);
        }

        public static SessionClaim completed(AiChatMessage message) {
            return new SessionClaim(ClaimState.COMPLETED, 0, 0, message);
        }

        public static SessionClaim busy() {
            return new SessionClaim(ClaimState.BUSY, 0, 0, null);
        }
    }

    private record SessionCoordination(long version, String activeRequestId,
                                       long activeRequestToken,
                                       LocalDateTime activeRequestExpireTime) {
    }

    private static Integer nullableInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}

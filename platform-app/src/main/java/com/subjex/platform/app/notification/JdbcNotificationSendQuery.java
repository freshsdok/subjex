package com.subjex.platform.app.notification;

import tools.jackson.databind.ObjectMapper;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationRecipient;
import com.subjex.platform.contract.notification.NotificationRequested;
import com.subjex.platform.contract.task.OriginKind;
import com.subjex.platform.contract.task.OutboxState;
import com.subjex.platform.contract.tenant.TenantMissingException;
import com.subjex.platform.contract.tenant.TenantState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcNotificationSendQuery — project {@code NotificationRequested} outbox rows for operator read.
 * 把已有 {@code NotificationRequested} 出箱行投影为操作员只读发送记录；必要时带上未丢弃的死信。
 * <p>
 * This is not a second durable store and does not enqueue, retry, or replay.
 * 这不是第二套持久台账，也不提供入队、重试或重放。
 */
public final class JdbcNotificationSendQuery {

    /** Default page size for operator lists — 操作员列表默认条数. */
    public static final int DEFAULT_LIST_LIMIT = 100;

    /** Hard cap on operator list size — 操作员列表条数上限. */
    public static final int MAX_LIST_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcNotificationSendQuery(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Tenant-scoped send records, newest first. Optional status, channel, recipient, and time-window filters.
     * 当前租户内发送记录，按时间从新到旧；可按状态、渠道、收件人和时间窗筛选。
     */
    public List<NotificationSendDocument> list(
            String tenantId,
            OutboxState status,
            NotificationChannel channel,
            String recipient,
            Instant from,
            Instant to,
            Integer limit) {
        String tenant = requireActiveBusinessTenant(tenantId);
        int cap = normalizeListLimit(limit);
        String needle = recipientNeedle(recipient);
        StringBuilder sql = new StringBuilder(
                "SELECT e.event_id, e.tenant_id, e.event_body, e.event_state, e.attempt_count, "
                        + "e.failure_reason, e.occurred_at, e.published_at, "
                        + "d.dead_letter_id, d.recorded_at AS dead_lettered_at "
                        + "FROM outbox_event e "
                        + "LEFT JOIN dead_letter d ON d.origin_kind = ? AND d.origin_id = e.event_id "
                        + "AND d.tenant_id = e.tenant_id AND d.discarded_at IS NULL "
                        + "WHERE e.tenant_id = ? AND e.event_name = ?");
        List<Object> args = new ArrayList<>();
        args.add(OriginKind.OUTBOX_EVENT.name());
        args.add(tenant);
        args.add(NotificationRequested.EVENT_NAME);
        if (status != null) {
            sql.append(" AND e.event_state = ?");
            args.add(status.name());
        }
        if (channel != null) {
            sql.append(" AND e.event_body LIKE ?");
            args.add(jsonFieldNeedle("channel", channel.name()));
        }
        if (needle != null) {
            sql.append(" AND (e.event_body LIKE ? OR e.event_body LIKE ?)");
            args.add(jsonFieldNeedle("subjectId", needle));
            args.add(jsonFieldNeedle("destination", needle));
        }
        if (from != null) {
            sql.append(" AND e.occurred_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND e.occurred_at <= ?");
            args.add(Timestamp.from(to));
        }
        sql.append(" ORDER BY e.occurred_at DESC, e.event_id DESC LIMIT ?");
        args.add(cap);
        return jdbc.query(sql.toString(), (row, n) -> toDocument(row), args.toArray());
    }

    /**
     * One {@code NotificationRequested} send record in the given tenant.
     * 按租户取一条 {@code NotificationRequested} 发送记录。
     */
    public NotificationSendDocument get(String tenantId, String eventId) {
        String tenant = requireActiveBusinessTenant(tenantId);
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId required");
        }
        String id = eventId.trim();
        List<NotificationSendRow> rows = jdbc.query(
                "SELECT e.event_id, e.tenant_id, e.event_name, e.event_body, e.event_state, e.attempt_count, "
                        + "e.failure_reason, e.occurred_at, e.published_at, "
                        + "d.dead_letter_id, d.recorded_at AS dead_lettered_at "
                        + "FROM outbox_event e "
                        + "LEFT JOIN dead_letter d ON d.origin_kind = ? AND d.origin_id = e.event_id "
                        + "AND d.tenant_id = e.tenant_id AND d.discarded_at IS NULL "
                        + "WHERE e.event_id = ?",
                (row, n) -> new NotificationSendRow(
                        row.getString("event_id"),
                        row.getString("tenant_id"),
                        row.getString("event_name"),
                        row.getString("event_body"),
                        row.getString("event_state"),
                        row.getInt("attempt_count"),
                        row.getString("failure_reason"),
                        instant(row.getTimestamp("occurred_at")),
                        instant(row.getTimestamp("published_at")),
                        row.getString("dead_letter_id"),
                        instant(row.getTimestamp("dead_lettered_at"))),
                OriginKind.OUTBOX_EVENT.name(),
                id);
        if (rows.isEmpty() || !NotificationRequested.EVENT_NAME.equals(rows.get(0).eventName())) {
            throw new NotificationSendNotFoundException(tenant, id);
        }
        NotificationSendRow found = rows.get(0);
        if (!tenant.equals(found.tenantId())) {
            throw new NotificationSendCrossTenantDeniedException(tenant, found.tenantId());
        }
        return toDocument(found);
    }

    private NotificationSendDocument toDocument(java.sql.ResultSet row) throws java.sql.SQLException {
        return toDocument(new NotificationSendRow(
                row.getString("event_id"),
                row.getString("tenant_id"),
                NotificationRequested.EVENT_NAME,
                row.getString("event_body"),
                row.getString("event_state"),
                row.getInt("attempt_count"),
                row.getString("failure_reason"),
                instant(row.getTimestamp("occurred_at")),
                instant(row.getTimestamp("published_at")),
                row.getString("dead_letter_id"),
                instant(row.getTimestamp("dead_lettered_at"))));
    }

    private NotificationSendDocument toDocument(NotificationSendRow row) {
        NotificationRequested body = readBody(row.eventBody());
        NotificationRecipient recipient = body == null ? null : body.recipient();
        return new NotificationSendDocument(
                row.eventId(),
                row.tenantId(),
                body == null ? null : body.templateId(),
                body == null || body.channel() == null ? null : body.channel().name(),
                recipient == null ? null : recipient.subjectId(),
                recipient == null || recipient.realm() == null ? null : recipient.realm().name(),
                recipient == null ? null : recipient.destination(),
                body == null || body.parameters() == null ? Map.of() : body.parameters(),
                row.eventState(),
                row.attemptCount(),
                row.failureReason(),
                row.occurredAt(),
                row.publishedAt(),
                row.deadLetterId(),
                row.deadLetteredAt());
    }

    private NotificationRequested readBody(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, NotificationRequested.class);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String requireActiveBusinessTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String id = tenantId.trim();
        if (JdbcNotificationPort.RESERVED_TENANT.equals(id)) {
            throw new IllegalArgumentException(
                    "reserved tenant " + JdbcNotificationPort.RESERVED_TENANT + " is not a notification home");
        }
        List<String> states = jdbc.query(
                "SELECT tenant_state FROM tenant WHERE tenant_id = ?",
                (row, n) -> row.getString("tenant_state"),
                id);
        if (states.isEmpty()) {
            throw new TenantNotFoundException(id);
        }
        if (!TenantState.ACTIVE.name().equals(states.get(0))) {
            throw new TenantDisabledException(id);
        }
        return id;
    }

    private static int normalizeListLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    private static String recipientNeedle(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return null;
        }
        String value = recipient.trim();
        rejectLikeMetacharacters(value);
        return value;
    }

    /**
     * Exact JSON field match via {@code LIKE}, rejecting wildcard characters so the filter stays literal.
     * 用 {@code LIKE} 做 JSON 字段精确匹配；拒绝通配符，避免筛选被当成模式。
     */
    static String jsonFieldNeedle(String field, String value) {
        return "%\"" + field + "\":\"" + value + "\"%";
    }

    private static void rejectLikeMetacharacters(String value) {
        if (value.indexOf('%') >= 0 || value.indexOf('_') >= 0 || value.indexOf('\\') >= 0 || value.indexOf('"') >= 0) {
            throw new IllegalArgumentException("recipient must not contain pattern characters");
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record NotificationSendRow(
            String eventId,
            String tenantId,
            String eventName,
            String eventBody,
            String eventState,
            int attemptCount,
            String failureReason,
            Instant occurredAt,
            Instant publishedAt,
            String deadLetterId,
            Instant deadLetteredAt) {}
}

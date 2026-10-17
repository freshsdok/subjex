package com.subjex.platform.app.inbox;

import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxEntry;
import com.subjex.platform.contract.inbox.InboxEntryView;
import com.subjex.platform.contract.inbox.InboxListQuery;
import com.subjex.platform.contract.inbox.InboxMessage;
import com.subjex.platform.contract.inbox.InboxPort;
import com.subjex.platform.contract.inbox.InboxRecipient;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.inbox.InboxSendRequest;
import com.subjex.platform.contract.inbox.InboxSendResult;
import com.subjex.platform.contract.inbox.InboxSeverity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcInboxPort — JDBC 站内信端口：{@link InboxPort} 的唯一实现（无 ORM）。
 * <p>
 * Sync write fan-out in one TX; every read filters tenant + realm + subject (fail-closed).
 * 同事务同步写扩散；每次读取按租户+领域+主体过滤（失败关闭）。
 */
public final class JdbcInboxPort implements InboxPort {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcInboxPort(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    @Override
    public InboxSendResult send(InboxSendRequest request) {
        Objects.requireNonNull(request, "request");
        List<InboxRecipient> unique = dedupeRecipients(request.recipients());
        String messageId = UUID.randomUUID().toString();
        Instant createdAt = request.createdAt();
        InboxMessage message = new InboxMessage(
                messageId,
                request.tenantId(),
                request.channel(),
                request.title(),
                request.body(),
                request.severity(),
                request.producerKind(),
                request.producerId(),
                request.refKind(),
                request.refId(),
                request.entityKey(),
                request.declarationVersion(),
                request.deepLink(),
                createdAt);
        List<InboxEntry> entries = new ArrayList<>(unique.size());
        for (InboxRecipient recipient : unique) {
            entries.add(new InboxEntry(
                    UUID.randomUUID().toString(),
                    messageId,
                    request.tenantId(),
                    recipient.realm(),
                    recipient.subjectId(),
                    null,
                    createdAt));
        }
        tx.executeWithoutResult(status -> {
            jdbc.update(
                    """
                    INSERT INTO inbox_message (
                        message_id, tenant_id, channel, title, body, severity,
                        producer_kind, producer_id, ref_kind, ref_id, entity_key,
                        declaration_version, deep_link, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    message.messageId(),
                    message.tenantId(),
                    message.channel().name(),
                    message.title(),
                    message.body(),
                    message.severity().name(),
                    message.producerKind(),
                    message.producerId(),
                    message.refKind(),
                    message.refId(),
                    message.entityKey(),
                    message.declarationVersion(),
                    message.deepLink(),
                    Timestamp.from(message.createdAt()));
            for (InboxEntry entry : entries) {
                jdbc.update(
                        """
                        INSERT INTO inbox_entry (
                            entry_id, message_id, tenant_id, recipient_realm,
                            recipient_subject_id, read_at, created_at)
                        VALUES (?, ?, ?, ?, ?, NULL, ?)
                        """,
                        entry.entryId(),
                        entry.messageId(),
                        entry.tenantId(),
                        entry.recipientRealm().name(),
                        entry.recipientSubjectId(),
                        Timestamp.from(entry.createdAt()));
            }
        });
        return new InboxSendResult(message, entries);
    }

    @Override
    public List<InboxEntryView> listEntries(
            String tenantId,
            InboxRecipientRealm realm,
            String recipientSubjectId,
            InboxListQuery query) {
        String tid = requireTenant(tenantId);
        InboxRecipientRealm r = Objects.requireNonNull(realm, "realm");
        String subject = requireSubject(recipientSubjectId);
        InboxListQuery q = query == null ? InboxListQuery.defaults() : query;
        StringBuilder sql = new StringBuilder(
                """
                SELECT e.entry_id, e.message_id, e.tenant_id, e.recipient_realm, e.recipient_subject_id,
                       e.read_at, e.created_at AS entry_created_at,
                       m.channel, m.title, m.body, m.severity, m.producer_kind, m.producer_id,
                       m.ref_kind, m.ref_id, m.entity_key, m.declaration_version, m.deep_link,
                       m.created_at AS message_created_at
                FROM inbox_entry e
                INNER JOIN inbox_message m ON m.message_id = e.message_id
                WHERE e.tenant_id = ? AND e.recipient_realm = ? AND e.recipient_subject_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tid);
        args.add(r.name());
        args.add(subject);
        if (q.unreadOnly()) {
            sql.append(" AND e.read_at IS NULL");
        }
        if (q.channel() != null) {
            sql.append(" AND m.channel = ?");
            args.add(q.channel().name());
        }
        sql.append(" ORDER BY e.created_at DESC");
        // LIMIT/OFFSET as literals: H2/MySQL/PG accept integers; avoid binding quirks in H2 MODE.
        // LIMIT/OFFSET 用字面量：三端都接受整数；避免 H2 绑定怪异。
        sql.append(" LIMIT ").append(q.limit());
        if (q.offset() > 0) {
            sql.append(" OFFSET ").append(q.offset());
        }
        return jdbc.query(sql.toString(), (rs, rowNum) -> mapView(rs), args.toArray());
    }

    @Override
    public long unreadCount(String tenantId, InboxRecipientRealm realm, String recipientSubjectId) {
        String tid = requireTenant(tenantId);
        InboxRecipientRealm r = Objects.requireNonNull(realm, "realm");
        String subject = requireSubject(recipientSubjectId);
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM inbox_entry
                WHERE tenant_id = ? AND recipient_realm = ? AND recipient_subject_id = ?
                  AND read_at IS NULL
                """,
                Long.class,
                tid,
                r.name(),
                subject);
        return count == null ? 0L : count;
    }

    @Override
    public boolean markRead(
            String tenantId,
            InboxRecipientRealm realm,
            String recipientSubjectId,
            String entryId,
            Instant readAt) {
        String tid = requireTenant(tenantId);
        InboxRecipientRealm r = Objects.requireNonNull(realm, "realm");
        String subject = requireSubject(recipientSubjectId);
        String eid = Objects.requireNonNull(entryId, "entryId").trim();
        Instant at = Objects.requireNonNull(readAt, "readAt");
        if (eid.isEmpty()) {
            return false;
        }
        // Idempotent: already-read rows match ownership but skip UPDATE; treat as success.
        // 幂等：已读行仍校验归属，跳过 UPDATE，视为成功。
        Integer owned = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM inbox_entry
                WHERE entry_id = ? AND tenant_id = ? AND recipient_realm = ?
                  AND recipient_subject_id = ?
                """,
                Integer.class,
                eid,
                tid,
                r.name(),
                subject);
        if (owned == null || owned == 0) {
            return false;
        }
        jdbc.update(
                """
                UPDATE inbox_entry
                SET read_at = ?
                WHERE entry_id = ? AND tenant_id = ? AND recipient_realm = ?
                  AND recipient_subject_id = ? AND read_at IS NULL
                """,
                Timestamp.from(at),
                eid,
                tid,
                r.name(),
                subject);
        return true;
    }

    @Override
    public Optional<InboxEntryView> getEntry(
            String tenantId, InboxRecipientRealm realm, String recipientSubjectId, String entryId) {
        String tid = requireTenant(tenantId);
        InboxRecipientRealm r = Objects.requireNonNull(realm, "realm");
        String subject = requireSubject(recipientSubjectId);
        String eid = Objects.requireNonNull(entryId, "entryId").trim();
        if (eid.isEmpty()) {
            return Optional.empty();
        }
        List<InboxEntryView> rows = jdbc.query(
                """
                SELECT e.entry_id, e.message_id, e.tenant_id, e.recipient_realm, e.recipient_subject_id,
                       e.read_at, e.created_at AS entry_created_at,
                       m.channel, m.title, m.body, m.severity, m.producer_kind, m.producer_id,
                       m.ref_kind, m.ref_id, m.entity_key, m.declaration_version, m.deep_link,
                       m.created_at AS message_created_at
                FROM inbox_entry e
                INNER JOIN inbox_message m ON m.message_id = e.message_id
                WHERE e.entry_id = ? AND e.tenant_id = ? AND e.recipient_realm = ?
                  AND e.recipient_subject_id = ?
                """,
                (rs, rowNum) -> mapView(rs),
                eid,
                tid,
                r.name(),
                subject);
        return rows.stream().findFirst();
    }


    @Override
    public Optional<InboxEntryView> findEntryInTenant(String tenantId, String entryId) {
        String tid = requireTenant(tenantId);
        String eid = Objects.requireNonNull(entryId, "entryId").trim();
        if (eid.isEmpty()) {
            return Optional.empty();
        }
        List<InboxEntryView> rows = jdbc.query(
                """
                SELECT e.entry_id, e.message_id, e.tenant_id, e.recipient_realm, e.recipient_subject_id,
                       e.read_at, e.created_at AS entry_created_at,
                       m.channel, m.title, m.body, m.severity, m.producer_kind, m.producer_id,
                       m.ref_kind, m.ref_id, m.entity_key, m.declaration_version, m.deep_link,
                       m.created_at AS message_created_at
                FROM inbox_entry e
                INNER JOIN inbox_message m ON m.message_id = e.message_id
                WHERE e.entry_id = ? AND e.tenant_id = ?
                """,
                (rs, rowNum) -> mapView(rs),
                eid,
                tid);
        return rows.stream().findFirst();
    }

    private static List<InboxRecipient> dedupeRecipients(List<InboxRecipient> recipients) {
        LinkedHashMap<String, InboxRecipient> seen = new LinkedHashMap<>();
        for (InboxRecipient recipient : recipients) {
            String key = recipient.realm().name() + "\0" + recipient.subjectId();
            seen.putIfAbsent(key, recipient);
        }
        return List.copyOf(seen.values());
    }

    private static String requireTenant(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        String tid = tenantId.trim();
        if (tid.isEmpty()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        return tid;
    }

    private static String requireSubject(String recipientSubjectId) {
        Objects.requireNonNull(recipientSubjectId, "recipientSubjectId");
        String subject = recipientSubjectId.trim();
        if (subject.isEmpty()) {
            throw new IllegalArgumentException("recipientSubjectId must not be blank");
        }
        return subject;
    }

    private static InboxEntryView mapView(ResultSet rs) throws SQLException {
        Instant entryCreated = rs.getTimestamp("entry_created_at").toInstant();
        Timestamp readTs = rs.getTimestamp("read_at");
        Instant readAt = readTs == null ? null : readTs.toInstant();
        InboxEntry entry = new InboxEntry(
                rs.getString("entry_id"),
                rs.getString("message_id"),
                rs.getString("tenant_id"),
                InboxRecipientRealm.valueOf(rs.getString("recipient_realm")),
                rs.getString("recipient_subject_id"),
                readAt,
                entryCreated);
        Integer declarationVersion = (Integer) rs.getObject("declaration_version");
        InboxMessage message = new InboxMessage(
                rs.getString("message_id"),
                rs.getString("tenant_id"),
                InboxChannel.valueOf(rs.getString("channel")),
                rs.getString("title"),
                rs.getString("body"),
                InboxSeverity.valueOf(rs.getString("severity")),
                rs.getString("producer_kind"),
                rs.getString("producer_id"),
                rs.getString("ref_kind"),
                rs.getString("ref_id"),
                rs.getString("entity_key"),
                declarationVersion,
                rs.getString("deep_link"),
                rs.getTimestamp("message_created_at").toInstant());
        return new InboxEntryView(entry, message);
    }
}

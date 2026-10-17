package com.subjex.platform.app.inbox;

import com.subjex.platform.contract.inbox.InboxAttachment;
import com.subjex.platform.contract.inbox.InboxAttachmentBind;
import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxEntry;
import com.subjex.platform.contract.inbox.InboxEntryView;
import com.subjex.platform.contract.inbox.InboxFanoutStatus;
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
 * Sync write fan-out in one TX (attachments + entries); deferred path stages recipients in
 * {@code inbox_fanout_*} and writes {@code inbox_entry} via {@link #processFanoutBatch}.
 * Every read filters tenant + realm + subject.
 * 同事务同步写扩散；异步路径暂存收件人后由批次写入 inbox_entry；读取按租户+领域+主体过滤。
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
        if (unique.size() > InboxSendRequest.SYNC_FANOUT_MAX) {
            throw new IllegalArgumentException(
                    "recipientCount="
                            + unique.size()
                            + " exceeds syncFanoutMax="
                            + InboxSendRequest.SYNC_FANOUT_MAX
                            + " (async fan-out IM-A6)");
        }
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
        List<InboxAttachment> attachments = new ArrayList<>(request.attachments().size());
        int order = 0;
        for (InboxAttachmentBind bind : request.attachments()) {
            int sort = bind.sortOrder() >= 0 ? bind.sortOrder() : order;
            attachments.add(new InboxAttachment(
                    UUID.randomUUID().toString(),
                    messageId,
                    request.tenantId(),
                    bind.fileName(),
                    bind.contentType(),
                    bind.byteSize(),
                    bind.storageKey(),
                    bind.checksumSha256(),
                    sort,
                    createdAt));
            order++;
        }
        List<InboxAttachment> bound = List.copyOf(attachments);
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
            for (InboxAttachment attachment : bound) {
                jdbc.update(
                        """
                        INSERT INTO inbox_attachment (
                            attachment_id, message_id, tenant_id, file_name, content_type,
                            byte_size, storage_key, checksum_sha256, sort_order, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        attachment.attachmentId(),
                        attachment.messageId(),
                        attachment.tenantId(),
                        attachment.fileName(),
                        attachment.contentType(),
                        attachment.byteSize(),
                        attachment.storageKey(),
                        attachment.checksumSha256(),
                        attachment.sortOrder(),
                        Timestamp.from(attachment.createdAt()));
            }
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
        return new InboxSendResult(message, entries, bound);
    }

    @Override
    public InboxSendResult sendDeferred(InboxSendRequest request) {
        Objects.requireNonNull(request, "request");
        List<InboxRecipient> unique = dedupeRecipients(request.recipients());
        if (unique.size() <= InboxSendRequest.SYNC_FANOUT_MAX) {
            throw new IllegalArgumentException(
                    "recipientCount="
                            + unique.size()
                            + " must exceed syncFanoutMax="
                            + InboxSendRequest.SYNC_FANOUT_MAX
                            + " for deferred fan-out; use send()");
        }
        if (unique.size() > InboxSendRequest.HARD_FANOUT_MAX) {
            throw new IllegalArgumentException(
                    "recipientCount="
                            + unique.size()
                            + " exceeds hardFanoutMax="
                            + InboxSendRequest.HARD_FANOUT_MAX);
        }
        String messageId = UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();
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
        List<InboxAttachment> attachments = new ArrayList<>(request.attachments().size());
        int order = 0;
        for (InboxAttachmentBind bind : request.attachments()) {
            int sort = bind.sortOrder() >= 0 ? bind.sortOrder() : order;
            attachments.add(new InboxAttachment(
                    UUID.randomUUID().toString(),
                    messageId,
                    request.tenantId(),
                    bind.fileName(),
                    bind.contentType(),
                    bind.byteSize(),
                    bind.storageKey(),
                    bind.checksumSha256(),
                    sort,
                    createdAt));
            order++;
        }
        List<InboxAttachment> bound = List.copyOf(attachments);
        List<InboxRecipient> staged = List.copyOf(unique);
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
            for (InboxAttachment attachment : bound) {
                jdbc.update(
                        """
                        INSERT INTO inbox_attachment (
                            attachment_id, message_id, tenant_id, file_name, content_type,
                            byte_size, storage_key, checksum_sha256, sort_order, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        attachment.attachmentId(),
                        attachment.messageId(),
                        attachment.tenantId(),
                        attachment.fileName(),
                        attachment.contentType(),
                        attachment.byteSize(),
                        attachment.storageKey(),
                        attachment.checksumSha256(),
                        attachment.sortOrder(),
                        Timestamp.from(attachment.createdAt()));
            }
            jdbc.update(
                    """
                    INSERT INTO inbox_fanout_job (
                        job_id, message_id, tenant_id, status, total_count, done_count,
                        error_message, created_at, updated_at)
                    VALUES (?, ?, ?, 'PENDING', ?, 0, NULL, ?, ?)
                    """,
                    jobId,
                    messageId,
                    request.tenantId(),
                    staged.size(),
                    Timestamp.from(createdAt),
                    Timestamp.from(createdAt));
            for (InboxRecipient recipient : staged) {
                jdbc.update(
                        """
                        INSERT INTO inbox_fanout_recipient (
                            job_id, recipient_realm, recipient_subject_id)
                        VALUES (?, ?, ?)
                        """,
                        jobId,
                        recipient.realm().name(),
                        recipient.subjectId());
            }
        });
        return new InboxSendResult(message, List.of(), bound, InboxFanoutStatus.PENDING, jobId);
    }

    @Override
    public int processFanoutBatch(String jobId, int batchSize) {
        String id = Objects.requireNonNull(jobId, "jobId").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        Integer written = tx.execute(status -> {
            List<JobRow> jobs = jdbc.query(
                    """
                    SELECT job_id, message_id, tenant_id, status, total_count, done_count, created_at
                    FROM inbox_fanout_job WHERE job_id = ?
                    """,
                    (rs, rowNum) -> new JobRow(
                            rs.getString("job_id"),
                            rs.getString("message_id"),
                            rs.getString("tenant_id"),
                            rs.getString("status"),
                            rs.getInt("total_count"),
                            rs.getInt("done_count"),
                            rs.getTimestamp("created_at").toInstant()),
                    id);
            if (jobs.isEmpty()) {
                return 0;
            }
            JobRow job = jobs.get(0);
            if ("DONE".equals(job.status()) || "FAILED".equals(job.status())) {
                return 0;
            }
            if ("PENDING".equals(job.status())) {
                jdbc.update(
                        """
                        UPDATE inbox_fanout_job
                        SET status = 'RUNNING', updated_at = ?
                        WHERE job_id = ? AND status = 'PENDING'
                        """,
                        Timestamp.from(Instant.now()),
                        id);
            }
            List<InboxRecipient> batch = jdbc.query(
                    """
                    SELECT recipient_realm, recipient_subject_id
                    FROM inbox_fanout_recipient
                    WHERE job_id = ?
                    ORDER BY recipient_realm, recipient_subject_id
                    LIMIT ?
                    """,
                    (rs, rowNum) -> new InboxRecipient(
                            InboxRecipientRealm.valueOf(rs.getString("recipient_realm")),
                            rs.getString("recipient_subject_id")),
                    id,
                    batchSize);
            if (batch.isEmpty()) {
                jdbc.update(
                        """
                        UPDATE inbox_fanout_job
                        SET status = 'DONE', updated_at = ?
                        WHERE job_id = ?
                        """,
                        Timestamp.from(Instant.now()),
                        id);
                return 0;
            }
            Instant createdAt = job.createdAt();
            int count = 0;
            for (InboxRecipient recipient : batch) {
                String entryId = UUID.randomUUID().toString();
                jdbc.update(
                        """
                        INSERT INTO inbox_entry (
                            entry_id, message_id, tenant_id, recipient_realm,
                            recipient_subject_id, read_at, created_at)
                        VALUES (?, ?, ?, ?, ?, NULL, ?)
                        """,
                        entryId,
                        job.messageId(),
                        job.tenantId(),
                        recipient.realm().name(),
                        recipient.subjectId(),
                        Timestamp.from(createdAt));
                jdbc.update(
                        """
                        DELETE FROM inbox_fanout_recipient
                        WHERE job_id = ? AND recipient_realm = ? AND recipient_subject_id = ?
                        """,
                        id,
                        recipient.realm().name(),
                        recipient.subjectId());
                count++;
            }
            jdbc.update(
                    """
                    UPDATE inbox_fanout_job
                    SET done_count = done_count + ?, updated_at = ?
                    WHERE job_id = ?
                    """,
                    count,
                    Timestamp.from(Instant.now()),
                    id);
            Integer remaining = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM inbox_fanout_recipient WHERE job_id = ?",
                    Integer.class,
                    id);
            if (remaining == null || remaining == 0) {
                jdbc.update(
                        """
                        UPDATE inbox_fanout_job
                        SET status = 'DONE', updated_at = ?
                        WHERE job_id = ?
                        """,
                        Timestamp.from(Instant.now()),
                        id);
            }
            return count;
        });
        return written == null ? 0 : written;
    }

    private record JobRow(
            String jobId,
            String messageId,
            String tenantId,
            String status,
            int totalCount,
            int doneCount,
            Instant createdAt) {}

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

    @Override
    public List<InboxAttachment> listAttachmentsForOwnEntry(
            String tenantId, InboxRecipientRealm realm, String recipientSubjectId, String entryId) {
        Optional<String> messageId = ownMessageId(tenantId, realm, recipientSubjectId, entryId);
        if (messageId.isEmpty()) {
            return List.of();
        }
        return listAttachmentsForMessage(requireTenant(tenantId), messageId.get());
    }

    @Override
    public Optional<InboxAttachment> findAttachmentForOwnEntry(
            String tenantId,
            InboxRecipientRealm realm,
            String recipientSubjectId,
            String entryId,
            String attachmentId) {
        Optional<String> messageId = ownMessageId(tenantId, realm, recipientSubjectId, entryId);
        if (messageId.isEmpty()) {
            return Optional.empty();
        }
        String aid = Objects.requireNonNull(attachmentId, "attachmentId").trim();
        if (aid.isEmpty()) {
            return Optional.empty();
        }
        List<InboxAttachment> rows = jdbc.query(
                """
                SELECT attachment_id, message_id, tenant_id, file_name, content_type, byte_size,
                       storage_key, checksum_sha256, sort_order, created_at
                FROM inbox_attachment
                WHERE attachment_id = ? AND message_id = ? AND tenant_id = ?
                """,
                (rs, rowNum) -> mapAttachment(rs),
                aid,
                messageId.get(),
                requireTenant(tenantId));
        return rows.stream().findFirst();
    }

    private Optional<String> ownMessageId(
            String tenantId, InboxRecipientRealm realm, String recipientSubjectId, String entryId) {
        String tid = requireTenant(tenantId);
        InboxRecipientRealm r = Objects.requireNonNull(realm, "realm");
        String subject = requireSubject(recipientSubjectId);
        String eid = Objects.requireNonNull(entryId, "entryId").trim();
        if (eid.isEmpty()) {
            return Optional.empty();
        }
        List<String> ids = jdbc.query(
                """
                SELECT message_id FROM inbox_entry
                WHERE entry_id = ? AND tenant_id = ? AND recipient_realm = ?
                  AND recipient_subject_id = ?
                """,
                (rs, rowNum) -> rs.getString("message_id"),
                eid,
                tid,
                r.name(),
                subject);
        return ids.stream().findFirst();
    }

    private List<InboxAttachment> listAttachmentsForMessage(String tenantId, String messageId) {
        return jdbc.query(
                """
                SELECT attachment_id, message_id, tenant_id, file_name, content_type, byte_size,
                       storage_key, checksum_sha256, sort_order, created_at
                FROM inbox_attachment
                WHERE message_id = ? AND tenant_id = ?
                ORDER BY sort_order ASC, created_at ASC
                """,
                (rs, rowNum) -> mapAttachment(rs),
                messageId,
                tenantId);
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

    private static InboxAttachment mapAttachment(ResultSet rs) throws SQLException {
        return new InboxAttachment(
                rs.getString("attachment_id"),
                rs.getString("message_id"),
                rs.getString("tenant_id"),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getInt("byte_size"),
                rs.getString("storage_key"),
                rs.getString("checksum_sha256"),
                rs.getInt("sort_order"),
                rs.getTimestamp("created_at").toInstant());
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

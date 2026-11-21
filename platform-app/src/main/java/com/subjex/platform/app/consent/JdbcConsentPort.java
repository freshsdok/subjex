package com.subjex.platform.app.consent;

import com.subjex.platform.app.jdbc.PlatformTables;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.contract.consent.ConsentCrossTenantDeniedException;
import com.subjex.platform.contract.consent.ConsentEvent;
import com.subjex.platform.contract.consent.ConsentEventKind;
import com.subjex.platform.contract.consent.ConsentPort;
import com.subjex.platform.contract.consent.ConsentRecord;
import com.subjex.platform.contract.consent.ConsentRequiredException;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcConsentPort — JDBC purpose-consent ledger: append-only events plus a transactional current projection.
 * JDBC 目的同意台账：追加式事件 + 同一事务维护的当前投影。
 * <p>
 * Grant and withdraw write {@link AuditPort} only; this slice writes no outbox events.
 * 授予与撤销只写 {@link AuditPort}；本切片不写出箱事件。
 */
public final class JdbcConsentPort implements ConsentPort {

    /** Reserved operator-home tenant; never a consent home. / 保留操作员归属租户，绝不可作为同意归属. */
    public static final String RESERVED_TENANT = "platform";

    /** Audit action for a successful grant — 授予成功的审计动作. */
    public static final String AUDIT_GRANT = "consent.grant";

    /** Audit action for a successful withdraw — 撤销成功的审计动作. */
    public static final String AUDIT_WITHDRAW = "consent.withdraw";

    private static final Pattern KEY = Pattern.compile(ConsentPort.KEY_PATTERN);

    private static final RowMapper<ConsentRecord> CURRENT_ROW = (row, n) -> new ConsentRecord(
            row.getString("tenant_id"),
            row.getString("subject_id"),
            row.getString("purpose_key"),
            row.getString("consent_version_id"),
            row.getString("event_id"),
            instant(row.getTimestamp("granted_at")));

    private static final RowMapper<ConsentEvent> EVENT_ROW = (row, n) -> new ConsentEvent(
            row.getString("event_id"),
            row.getString("tenant_id"),
            row.getString("subject_id"),
            row.getString("purpose_key"),
            row.getString("consent_version_id"),
            ConsentEventKind.valueOf(row.getString("event_kind")),
            row.getString("actor_identity_id"),
            instant(row.getTimestamp("occurred_at")));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final AuditPort audit;

    public JdbcConsentPort(
            JdbcTemplate jdbc, TransactionTemplate transactionTemplate, Clock clock, AuditPort audit) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @Override
    public ConsentRecord grant(
            String tenantId, String subjectId, String purposeKey, String consentVersionId, String actorIdentityId) {
        String tid = requireActiveBusinessTenant(tenantId);
        String sid = requireSubjectInTenant(tid, subjectId);
        String purpose = requireKey(purposeKey, "purposeKey");
        String version = requireKey(consentVersionId, "consentVersionId");
        String actor = requireId(actorIdentityId, "actorIdentityId");
        Instant now = clock.instant();
        String eventId = UUID.randomUUID().toString();
        Timestamp occurred = PlatformTables.timestamp(now);
        ConsentRecord record = transactionTemplate.execute(status -> {
            jdbc.update(
                    """
                    INSERT INTO consent_event (
                        event_id, tenant_id, subject_id, purpose_key, consent_version_id,
                        event_kind, actor_identity_id, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    eventId,
                    tid,
                    sid,
                    purpose,
                    version,
                    ConsentEventKind.GRANT.name(),
                    actor,
                    occurred);
            jdbc.update(
                    "DELETE FROM consent_current WHERE tenant_id = ? AND subject_id = ? AND purpose_key = ?",
                    tid,
                    sid,
                    purpose);
            jdbc.update(
                    """
                    INSERT INTO consent_current (
                        tenant_id, subject_id, purpose_key, consent_version_id, event_id, granted_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    tid,
                    sid,
                    purpose,
                    version,
                    eventId,
                    occurred);
            return new ConsentRecord(tid, sid, purpose, version, eventId, now);
        });
        audit.record(new AuditEntry(
                UUID.randomUUID().toString(),
                tid,
                actor,
                AUDIT_GRANT,
                purpose + "@" + version,
                AuditOutcome.ALLOWED,
                now));
        return Objects.requireNonNull(record, "grant");
    }

    @Override
    public ConsentEvent withdraw(String tenantId, String subjectId, String purposeKey, String actorIdentityId) {
        String tid = requireActiveBusinessTenant(tenantId);
        String sid = requireSubjectInTenant(tid, subjectId);
        String purpose = requireKey(purposeKey, "purposeKey");
        String actor = requireId(actorIdentityId, "actorIdentityId");
        Instant now = clock.instant();
        ConsentEvent event = transactionTemplate.execute(status -> {
            ConsentRecord current = loadCurrent(tid, sid, purpose)
                    .orElseThrow(() -> new IllegalArgumentException("no active grant to withdraw"));
            String eventId = UUID.randomUUID().toString();
            Timestamp occurred = PlatformTables.timestamp(now);
            jdbc.update(
                    """
                    INSERT INTO consent_event (
                        event_id, tenant_id, subject_id, purpose_key, consent_version_id,
                        event_kind, actor_identity_id, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    eventId,
                    tid,
                    sid,
                    purpose,
                    current.consentVersionId(),
                    ConsentEventKind.WITHDRAW.name(),
                    actor,
                    occurred);
            jdbc.update(
                    "DELETE FROM consent_current WHERE tenant_id = ? AND subject_id = ? AND purpose_key = ?",
                    tid,
                    sid,
                    purpose);
            return new ConsentEvent(
                    eventId,
                    tid,
                    sid,
                    purpose,
                    current.consentVersionId(),
                    ConsentEventKind.WITHDRAW,
                    actor,
                    now);
        });
        ConsentEvent written = Objects.requireNonNull(event, "withdraw");
        audit.record(new AuditEntry(
                UUID.randomUUID().toString(),
                tid,
                actor,
                AUDIT_WITHDRAW,
                purpose + "@" + written.consentVersionId(),
                AuditOutcome.ALLOWED,
                now));
        return written;
    }

    @Override
    public Optional<ConsentRecord> current(String tenantId, String subjectId, String purposeKey) {
        String tid = requireActiveBusinessTenant(tenantId);
        String sid = requireSubjectInTenant(tid, subjectId);
        String purpose = requireKey(purposeKey, "purposeKey");
        return loadCurrent(tid, sid, purpose);
    }

    @Override
    public List<ConsentRecord> listCurrent(String tenantId, String subjectId) {
        String tid = requireActiveBusinessTenant(tenantId);
        String sid = requireSubjectInTenant(tid, subjectId);
        return jdbc.query(
                """
                SELECT tenant_id, subject_id, purpose_key, consent_version_id, event_id, granted_at
                FROM consent_current
                WHERE tenant_id = ? AND subject_id = ?
                ORDER BY purpose_key
                """,
                CURRENT_ROW,
                tid,
                sid);
    }

    @Override
    public List<ConsentEvent> history(String tenantId, String subjectId, String purposeKey) {
        String tid = requireActiveBusinessTenant(tenantId);
        String sid = requireSubjectInTenant(tid, subjectId);
        if (purposeKey == null || purposeKey.isBlank()) {
            return jdbc.query(
                    """
                    SELECT event_id, tenant_id, subject_id, purpose_key, consent_version_id,
                           event_kind, actor_identity_id, occurred_at
                    FROM consent_event
                    WHERE tenant_id = ? AND subject_id = ?
                    ORDER BY occurred_at ASC, event_id ASC
                    """,
                    EVENT_ROW,
                    tid,
                    sid);
        }
        String purpose = requireKey(purposeKey, "purposeKey");
        return jdbc.query(
                """
                SELECT event_id, tenant_id, subject_id, purpose_key, consent_version_id,
                       event_kind, actor_identity_id, occurred_at
                FROM consent_event
                WHERE tenant_id = ? AND subject_id = ? AND purpose_key = ?
                ORDER BY occurred_at ASC, event_id ASC
                """,
                EVENT_ROW,
                tid,
                sid,
                purpose);
    }

    @Override
    public void require(String tenantId, String subjectId, String purposeKey, String requiredVersion) {
        Optional<ConsentRecord> current;
        try {
            current = current(tenantId, subjectId, purposeKey);
        } catch (RuntimeException ex) {
            throw ex;
        }
        if (current.isEmpty()) {
            throw new ConsentRequiredException();
        }
        if (requiredVersion != null && !requiredVersion.isBlank()) {
            String expected = requireKey(requiredVersion, "requiredVersion");
            if (!expected.equals(current.get().consentVersionId())) {
                throw new ConsentRequiredException();
            }
        }
    }

    private Optional<ConsentRecord> loadCurrent(String tenantId, String subjectId, String purposeKey) {
        List<ConsentRecord> rows = jdbc.query(
                """
                SELECT tenant_id, subject_id, purpose_key, consent_version_id, event_id, granted_at
                FROM consent_current
                WHERE tenant_id = ? AND subject_id = ? AND purpose_key = ?
                """,
                CURRENT_ROW,
                tenantId,
                subjectId,
                purposeKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Explicit business tenant, ACTIVE, never reserved {@code platform}, never auto-created.
     * 必须是明确的业务租户且为 ACTIVE，绝不用保留租户 {@code platform}，也绝不自动建户。
     */
    private String requireActiveBusinessTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String id = tenantId.trim();
        if (RESERVED_TENANT.equals(id)) {
            throw new IllegalArgumentException(
                    "reserved tenant " + RESERVED_TENANT + " is not a consent home");
        }
        List<String> states = jdbc.query(
                "SELECT tenant_state FROM tenant WHERE tenant_id = ?",
                (row, n) -> row.getString("tenant_state"),
                id);
        if (states.isEmpty()) {
            throw new TenantNotFoundException(id);
        }
        String state = states.get(0) == null ? "" : states.get(0).trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(state)) {
            throw new TenantDisabledException(id);
        }
        return id;
    }

    /**
     * Subject must have an ACTIVE identity in this tenant; otherwise treat as cross-tenant.
     * 主体必须在该租户内有 ACTIVE 身份，否则视为跨租户访问。
     */
    private String requireSubjectInTenant(String tenantId, String subjectId) {
        String sid = requireId(subjectId, "subjectId");
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM subject_identity
                WHERE tenant_id = ? AND subject_id = ? AND identity_state = 'ACTIVE'
                """,
                Integer.class,
                tenantId,
                sid);
        if (count == null || count == 0) {
            throw new ConsentCrossTenantDeniedException();
        }
        return sid;
    }

    static String requireKey(String value, String label) {
        String key = requireId(value, label);
        if (key.length() > ConsentPort.MAX_KEY_LENGTH || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    label + " must match " + ConsentPort.KEY_PATTERN + " and be at most "
                            + ConsentPort.MAX_KEY_LENGTH + " characters");
        }
        return key;
    }

    private static String requireId(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}

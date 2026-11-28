package com.subjex.platform.app.points;

import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.points.AdjustPointsCommand;
import com.subjex.platform.contract.points.PointsAccountDocument;
import com.subjex.platform.contract.points.PointsActorKind;
import com.subjex.platform.contract.points.PointsDirection;
import com.subjex.platform.contract.points.PointsIdempotencyConflictException;
import com.subjex.platform.contract.points.PointsInsufficientException;
import com.subjex.platform.contract.points.PointsLedgerEntry;
import com.subjex.platform.contract.points.PointsPort;
import com.subjex.platform.contract.points.PointsSubjectNotFoundException;
import com.subjex.platform.contract.tenant.TenantMissingException;
import com.subjex.platform.contract.tenant.TenantState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcPointsPort — tenant-scoped append-only points ledger with a transactional balance projection.
 * 按租户的只追加积分台账，并以事务内余额投影。
 * <p>
 * Deduct refuses an overdraft (no signed / negative balances). Reserved tenant {@code platform}
 * is never a business home. There is no automatic grant on payment success.
 * 扣减拒绝透支（不支持负余额）。保留租户 platform 不是业务归属。支付成功不会自动发放。
 */
public final class JdbcPointsPort implements PointsPort {

    /**
     * Reserved operator-home tenant; never a default business points tenant.
     * 保留的操作员归属租户；绝不可作为默认业务积分租户。
     */
    public static final String RESERVED_TENANT = "platform";

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public JdbcPointsPort(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PointsAccountDocument getAccount(String tenantId, String subjectId, int limit) {
        String tenant = requireActiveBusinessTenant(tenantId);
        String subject = requireSubjectId(subjectId);
        requireActiveIdentity(tenant, subject);
        return loadAccount(tenant, subject, clampLimit(limit));
    }

    @Override
    public PointsAccountDocument adjust(AdjustPointsCommand command) {
        Objects.requireNonNull(command, "command");
        String tenant = requireActiveBusinessTenant(command.tenantId());
        requireActiveIdentity(tenant, command.subjectId());
        String fingerprint = fingerprint(command);
        return transactions.execute(status -> {
            Optional<PointsLedgerEntry> existing = findByToken(tenant, command.idempotencyToken());
            if (existing.isPresent()) {
                assertFingerprint(tenant, command.idempotencyToken(), fingerprint);
                return loadAccount(tenant, command.subjectId(), DEFAULT_LIMIT);
            }
            try {
                applyAdjustment(command, tenant, fingerprint);
            } catch (DuplicateKeyException ex) {
                assertFingerprint(tenant, command.idempotencyToken(), fingerprint);
            }
            return loadAccount(tenant, command.subjectId(), DEFAULT_LIMIT);
        });
    }

    private void applyAdjustment(AdjustPointsCommand command, String tenant, String fingerprint) {
        Instant now = clock.instant();
        Long locked = lockBalance(tenant, command.subjectId());
        long current;
        if (locked == null) {
            if (command.direction() == PointsDirection.DEDUCT) {
                throw new PointsInsufficientException(
                        "insufficient points in tenant " + tenant + " for subject " + command.subjectId());
            }
            try {
                jdbc.update(
                        "INSERT INTO points_account (tenant_id, subject_id, balance, updated_at) VALUES (?, ?, 0, ?)",
                        tenant,
                        command.subjectId(),
                        Timestamp.from(now));
            } catch (DuplicateKeyException ex) {
                // A concurrent first grant won the insert; lock the row it created.
                // 并发的首次发放已插入账户；改为锁住该行。
            }
            Long afterInsert = lockBalance(tenant, command.subjectId());
            if (afterInsert == null) {
                throw new IllegalStateException("points account missing after insert");
            }
            current = afterInsert;
        } else {
            current = locked;
        }
        long next = current + command.signedDelta();
        if (next < 0) {
            throw new PointsInsufficientException(
                    "insufficient points in tenant " + tenant + " for subject " + command.subjectId());
        }
        String entryId = "pts-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                "INSERT INTO points_ledger ("
                        + "entry_id, tenant_id, subject_id, delta, balance_after, reason, memo, "
                        + "reference_type, reference_id, actor_subject_id, actor_kind, "
                        + "idempotency_token, idempotency_fingerprint, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                entryId,
                tenant,
                command.subjectId(),
                command.signedDelta(),
                next,
                command.reason(),
                command.memo(),
                command.reference() == null ? null : command.reference().type(),
                command.reference() == null ? null : command.reference().id(),
                command.actorSubjectId(),
                command.actorKind().name(),
                command.idempotencyToken(),
                fingerprint,
                Timestamp.from(now));
        jdbc.update(
                "UPDATE points_account SET balance = ?, updated_at = ? WHERE tenant_id = ? AND subject_id = ?",
                next,
                Timestamp.from(now),
                tenant,
                command.subjectId());
    }

    private PointsAccountDocument loadAccount(String tenant, String subject, int limit) {
        List<Long> balances = jdbc.query(
                "SELECT balance FROM points_account WHERE tenant_id = ? AND subject_id = ?",
                (row, n) -> row.getLong("balance"),
                tenant,
                subject);
        long balance = balances.isEmpty() ? 0L : balances.get(0);
        List<PointsLedgerEntry> entries = jdbc.query(
                "SELECT entry_id, tenant_id, subject_id, delta, balance_after, reason, memo, "
                        + "reference_type, reference_id, actor_subject_id, actor_kind, created_at "
                        + "FROM points_ledger WHERE tenant_id = ? AND subject_id = ? "
                        + "ORDER BY created_at DESC, entry_id DESC LIMIT ?",
                this::mapEntry,
                tenant,
                subject,
                limit);
        return new PointsAccountDocument(tenant, subject, balance, entries);
    }

    private PointsLedgerEntry mapEntry(ResultSet row, int index) throws SQLException {
        long delta = row.getLong("delta");
        Timestamp created = row.getTimestamp("created_at");
        return new PointsLedgerEntry(
                row.getString("entry_id"),
                row.getString("tenant_id"),
                row.getString("subject_id"),
                delta,
                row.getLong("balance_after"),
                delta > 0 ? PointsDirection.GRANT : PointsDirection.DEDUCT,
                row.getString("reason"),
                row.getString("memo"),
                row.getString("reference_type"),
                row.getString("reference_id"),
                row.getString("actor_subject_id"),
                PointsActorKind.valueOf(row.getString("actor_kind")),
                created == null ? null : created.toInstant());
    }

    private Long lockBalance(String tenant, String subject) {
        List<Long> rows = jdbc.query(
                "SELECT balance FROM points_account WHERE tenant_id = ? AND subject_id = ? FOR UPDATE",
                (row, n) -> row.getLong("balance"),
                tenant,
                subject);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Optional<PointsLedgerEntry> findByToken(String tenant, String token) {
        List<PointsLedgerEntry> rows = jdbc.query(
                "SELECT entry_id, tenant_id, subject_id, delta, balance_after, reason, memo, "
                        + "reference_type, reference_id, actor_subject_id, actor_kind, created_at "
                        + "FROM points_ledger WHERE tenant_id = ? AND idempotency_token = ?",
                this::mapEntry,
                tenant,
                token);
        return rows.stream().findFirst();
    }

    private void assertFingerprint(String tenant, String token, String fingerprint) {
        List<String> stored = jdbc.query(
                "SELECT idempotency_fingerprint FROM points_ledger "
                        + "WHERE tenant_id = ? AND idempotency_token = ?",
                (row, n) -> row.getString("idempotency_fingerprint"),
                tenant,
                token);
        if (stored.isEmpty()) {
            throw new PointsIdempotencyConflictException(
                    "idempotency token missing after conflict in tenant " + tenant);
        }
        if (!fingerprint.equals(stored.get(0))) {
            throw new PointsIdempotencyConflictException(
                    "idempotency token reused with a different adjustment in tenant " + tenant + " token " + token);
        }
    }

    private void requireActiveIdentity(String tenant, String subject) {
        Integer found = jdbc.query(
                "SELECT 1 FROM subject_identity WHERE tenant_id = ? AND subject_id = ? AND identity_state = 'ACTIVE'",
                rs -> rs.next() ? 1 : null,
                tenant,
                subject);
        if (found == null) {
            throw new PointsSubjectNotFoundException(
                    "no ACTIVE identity for subject " + subject + " in tenant " + tenant);
        }
    }

    /**
     * Reuses the same fail-closed checks as payment and notification ports.
     * 复用与支付、通知端口相同的失败关闭校验。
     */
    private String requireActiveBusinessTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String id = tenantId.trim();
        if (RESERVED_TENANT.equals(id)) {
            throw new IllegalArgumentException("reserved tenant " + RESERVED_TENANT + " is not a points home");
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

    private static String requireSubjectId(String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("subject id is required");
        }
        return subjectId.trim();
    }

    static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    static String fingerprint(AdjustPointsCommand command) {
        String refType = command.reference() == null ? "" : command.reference().type();
        String refId = command.reference() == null ? "" : command.reference().id();
        return sha256(
                command.subjectId()
                        + "|"
                        + command.direction().name()
                        + "|"
                        + command.amount()
                        + "|"
                        + command.reason()
                        + "|"
                        + nullToEmpty(command.memo())
                        + "|"
                        + refType
                        + "|"
                        + refId);
    }

    private static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 required for points fingerprint", ex);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

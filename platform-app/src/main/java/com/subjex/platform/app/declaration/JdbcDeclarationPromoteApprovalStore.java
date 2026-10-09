package com.subjex.platform.app.declaration;

import com.subjex.platform.app.jdbc.PlatformTables;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcDeclarationPromoteApprovalStore — 晋升双人确认：第一人请求，第二人消费后才可晋升（P7）。
 */
public final class JdbcDeclarationPromoteApprovalStore {

    public static final String PENDING = "PENDING";
    public static final String CONSUMED = "CONSUMED";
    public static final String CANCELLED = "CANCELLED";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    private static final RowMapper<DeclarationPromoteApproval> ROW = (rs, i) -> new DeclarationPromoteApproval(
            rs.getString("approval_id"),
            rs.getString("tenant_id"),
            DeclarationKind.fromWire(rs.getString("declaration_kind")),
            rs.getString("declaration_key"),
            rs.getInt("revision"),
            rs.getString("requested_by_subject_id"),
            rs.getString("approval_state"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
            rs.getString("consumed_by_subject_id"));

    public JdbcDeclarationPromoteApprovalStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DeclarationPromoteApproval request(
            String tenantId,
            DeclarationKind kind,
            String declarationKey,
            int revision,
            String requestedBySubjectId) {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be >= 1");
        }
        String id = UUID.randomUUID().toString();
        Instant at = clock.instant();
        jdbc.update(
                """
                INSERT INTO declaration_promote_approval
                  (approval_id, tenant_id, declaration_kind, declaration_key, revision,
                   requested_by_subject_id, approval_state, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                tenantId.trim(),
                kind.wireName(),
                declarationKey.trim(),
                revision,
                requestedBySubjectId.trim(),
                PENDING,
                PlatformTables.timestamp(at));
        return new DeclarationPromoteApproval(
                id, tenantId.trim(), kind, declarationKey.trim(), revision,
                requestedBySubjectId.trim(), PENDING, at, null, null);
    }

    public Optional<DeclarationPromoteApproval> find(String approvalId) {
        return jdbc.query(
                        """
                        SELECT approval_id, tenant_id, declaration_kind, declaration_key, revision,
                               requested_by_subject_id, approval_state, created_at,
                               consumed_at, consumed_by_subject_id
                        FROM declaration_promote_approval WHERE approval_id = ?
                        """,
                        ROW,
                        approvalId)
                .stream()
                .findFirst();
    }

    /**
     * Consume a PENDING approval by a different operator — 由另一名操作员消费 PENDING 确认。
     */
    public DeclarationPromoteApproval consume(String approvalId, String consumerSubjectId) {
        DeclarationPromoteApproval row = find(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("approval not found: " + approvalId));
        if (!PENDING.equals(row.approvalState())) {
            throw new IllegalStateException("approval not PENDING: " + row.approvalState());
        }
        if (row.requestedBySubjectId().equals(consumerSubjectId)) {
            throw new DeclarationPromoteNeedsSecondOperator(
                    "promote requires a second operator with declaration.promote (requester cannot self-confirm)");
        }
        Instant at = clock.instant();
        int updated = jdbc.update(
                """
                UPDATE declaration_promote_approval
                SET approval_state = ?, consumed_at = ?, consumed_by_subject_id = ?
                WHERE approval_id = ? AND approval_state = ?
                """,
                CONSUMED,
                PlatformTables.timestamp(at),
                consumerSubjectId.trim(),
                approvalId,
                PENDING);
        if (updated != 1) {
            throw new IllegalStateException("approval consume race for " + approvalId);
        }
        return find(approvalId).orElseThrow();
    }
}

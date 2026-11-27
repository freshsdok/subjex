package com.subjex.platform.app.exporthook;

import com.subjex.platform.app.jdbc.PlatformTables;
import com.subjex.platform.contract.exporthook.ExportRequest;
import com.subjex.platform.contract.exporthook.ExportRequestStatus;
import com.subjex.platform.contract.exporthook.ExportRequesterRealm;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcExportRequestStore — JDBC persistence for {@link ExportRequest} rows (WP8-2).
 * 导出申请行的 JDBC 存储。
 */
public final class JdbcExportRequestStore {

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static final RowMapper<ExportRequest> ROW = (row, n) -> new ExportRequest(
            row.getString("request_id"),
            row.getString("tenant_id"),
            row.getString("subject_id"),
            ExportRequesterRealm.valueOf(row.getString("requester_realm")),
            row.getString("requester_id"),
            row.getString("scope_descriptor"),
            ExportRequestStatus.valueOf(row.getString("request_status")),
            row.getString("approver_subject_id"),
            row.getString("workflow_instance_id"),
            instant(row.getTimestamp("created_at")),
            instant(row.getTimestamp("updated_at")),
            instant(row.getTimestamp("decided_at")),
            instant(row.getTimestamp("expires_at")),
            row.getString("artifact_object_key"));

    private final JdbcTemplate jdbc;

    public JdbcExportRequestStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public void insert(ExportRequest request) {
        Objects.requireNonNull(request, "request");
        jdbc.update(
                """
                INSERT INTO data_export_request (
                    request_id, tenant_id, subject_id, requester_realm, requester_id,
                    scope_descriptor, request_status, approver_subject_id, workflow_instance_id,
                    created_at, updated_at, decided_at, expires_at, artifact_object_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                request.requestId(),
                request.tenantId(),
                request.subjectId(),
                request.requesterRealm().name(),
                request.requesterId(),
                request.scopeDescriptor(),
                request.status().name(),
                request.approverSubjectId(),
                request.workflowInstanceId(),
                PlatformTables.timestamp(request.createdAt()),
                PlatformTables.timestamp(request.updatedAt()),
                PlatformTables.timestamp(request.decidedAt()),
                PlatformTables.timestamp(request.expiresAt()),
                request.artifactObjectKey());
    }

    public Optional<ExportRequest> find(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        List<ExportRequest> rows = jdbc.query(
                "SELECT * FROM data_export_request WHERE request_id = ?", ROW, requestId.trim());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Short row lock used only while taking or finishing a generation lease, not during the stream write.
     * 仅在领取或结束生成租约时短时锁行，写入过程中不持锁。
     */
    public Optional<ExportRequest> findForUpdate(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        List<ExportRequest> rows = jdbc.query(
                "SELECT * FROM data_export_request WHERE request_id = ? FOR UPDATE",
                ROW,
                requestId.trim());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Subject-scoped list, newest first, exclusive keyset cursor, already clamped limit.
     * 按主体列出，从新到旧，游标为开区间，条数须已夹紧。
     */
    public List<ExportRequest> listBySubject(
            String tenantId, String subjectId, java.time.Instant afterCreatedAt, String afterRequestId, int limit) {
        if (afterCreatedAt != null && afterRequestId != null && !afterRequestId.isBlank()) {
            return jdbc.query(
                    """
                    SELECT * FROM data_export_request
                    WHERE tenant_id = ? AND subject_id = ?
                      AND (created_at < ? OR (created_at = ? AND request_id < ?))
                    ORDER BY created_at DESC, request_id DESC
                    LIMIT ?
                    """,
                    ROW,
                    tenantId,
                    subjectId,
                    PlatformTables.timestamp(afterCreatedAt),
                    PlatformTables.timestamp(afterCreatedAt),
                    afterRequestId.trim(),
                    limit);
        }
        return jdbc.query(
                """
                SELECT * FROM data_export_request
                WHERE tenant_id = ? AND subject_id = ?
                ORDER BY created_at DESC, request_id DESC
                LIMIT ?
                """,
                ROW,
                tenantId,
                subjectId,
                limit);
    }

    /**
     * Tenant-scoped list, newest first, exclusive keyset cursor, already clamped limit.
     * 按租户列出，从新到旧，游标为开区间，条数须已夹紧。
     */
    public List<ExportRequest> listByTenant(
            String tenantId, java.time.Instant afterCreatedAt, String afterRequestId, int limit) {
        if (afterCreatedAt != null && afterRequestId != null && !afterRequestId.isBlank()) {
            return jdbc.query(
                    """
                    SELECT * FROM data_export_request
                    WHERE tenant_id = ?
                      AND (created_at < ? OR (created_at = ? AND request_id < ?))
                    ORDER BY created_at DESC, request_id DESC
                    LIMIT ?
                    """,
                    ROW,
                    tenantId,
                    PlatformTables.timestamp(afterCreatedAt),
                    PlatformTables.timestamp(afterCreatedAt),
                    afterRequestId.trim(),
                    limit);
        }
        return jdbc.query(
                """
                SELECT * FROM data_export_request
                WHERE tenant_id = ?
                ORDER BY created_at DESC, request_id DESC
                LIMIT ?
                """,
                ROW,
                tenantId,
                limit);
    }

    /**
     * Oldest queued generation rows for the thin worker — 薄工作线程领取最早入队的生成中申请.
     */
    public List<ExportRequest> listByStatus(ExportRequestStatus status, int limit) {
        return jdbc.query(
                """
                SELECT * FROM data_export_request
                WHERE request_status = ?
                ORDER BY updated_at ASC, request_id ASC
                LIMIT ?
                """,
                ROW,
                status.name(),
                limit);
    }

    public void updateDecision(ExportRequest request) {
        jdbc.update(
                """
                UPDATE data_export_request
                   SET request_status = ?, approver_subject_id = ?, updated_at = ?, decided_at = ?,
                       generation_lease_owner = NULL, generation_lease_expires_at = NULL
                 WHERE request_id = ?
                """,
                request.status().name(),
                request.approverSubjectId(),
                PlatformTables.timestamp(request.updatedAt()),
                PlatformTables.timestamp(request.decidedAt()),
                request.requestId());
    }

    /** Persist generation or expiry outcome — 写入生成或过期结果. */
    public void updateArtifact(ExportRequest request) {
        jdbc.update(
                """
                UPDATE data_export_request
                   SET request_status = ?, artifact_object_key = ?, expires_at = ?, updated_at = ?,
                       generation_lease_owner = NULL, generation_lease_expires_at = NULL
                 WHERE request_id = ?
                """,
                request.status().name(),
                request.artifactObjectKey(),
                PlatformTables.timestamp(request.expiresAt()),
                PlatformTables.timestamp(request.updatedAt()),
                request.requestId());
    }

    public List<ExportRequest> listReadyExpired(java.time.Instant now) {
        return jdbc.query(
                """
                SELECT * FROM data_export_request
                WHERE request_status = ? AND expires_at IS NOT NULL AND expires_at < ?
                """,
                ROW,
                ExportRequestStatus.READY.name(),
                PlatformTables.timestamp(now));
    }


    /**
     * GENERATING rows whose lease is missing or already expired, oldest first.
     * 租约缺失或已过期的 GENERATING 行，从早到晚。
     */
    public List<ExportRequest> listClaimable(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        return jdbc.query(
                """
                SELECT * FROM data_export_request
                WHERE request_status = ?
                  AND (generation_lease_owner IS NULL
                       OR generation_lease_expires_at IS NULL
                       OR generation_lease_expires_at <= ?)
                ORDER BY updated_at ASC, request_id ASC
                LIMIT ?
                """,
                ROW,
                ExportRequestStatus.GENERATING.name(),
                PlatformTables.timestamp(now),
                limit);
    }

    /**
     * Take a short FOR UPDATE, write the lease, then let the caller commit so the lock is released.
     * Another owner cannot take a still-valid lease. Must run inside a transaction.
     * 短时 FOR UPDATE 写入租约，由调用方提交以释放行锁。有效租约不可被其他持有人抢走。必须在事务中调用。
     */
    public Optional<ExportRequest> claimGeneration(String requestId, String owner, Instant now, Duration ttl) {
        if (requestId == null || requestId.isBlank() || owner == null || owner.isBlank()) {
            return Optional.empty();
        }
        Objects.requireNonNull(now, "now");
        Duration leaseTtl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofMinutes(2) : ttl;
        Optional<ExportRequest> locked = findForUpdate(requestId.trim());
        if (locked.isEmpty() || locked.get().status() != ExportRequestStatus.GENERATING) {
            return Optional.empty();
        }
        List<Boolean> held = jdbc.query(
                """
                SELECT 1 FROM data_export_request
                WHERE request_id = ?
                  AND generation_lease_owner IS NOT NULL
                  AND generation_lease_expires_at IS NOT NULL
                  AND generation_lease_expires_at > ?
                """,
                (row, n) -> Boolean.TRUE,
                requestId.trim(),
                PlatformTables.timestamp(now));
        if (!held.isEmpty()) {
            return Optional.empty();
        }
        Instant expires = now.plus(leaseTtl);
        jdbc.update(
                """
                UPDATE data_export_request
                   SET generation_lease_owner = ?, generation_lease_expires_at = ?, updated_at = ?
                 WHERE request_id = ? AND request_status = ?
                """,
                owner.trim(),
                PlatformTables.timestamp(expires),
                PlatformTables.timestamp(now),
                requestId.trim(),
                ExportRequestStatus.GENERATING.name());
        return find(requestId.trim());
    }

    /**
     * Extend a still-valid lease held by {@code owner}. Returns false if the lease was lost.
     * 延长 {@code owner} 仍然有效的租约；若已丢失则返回 false。
     */
    public boolean renewGenerationLease(String requestId, String owner, Instant now, Duration ttl) {
        if (requestId == null || requestId.isBlank() || owner == null || owner.isBlank()) {
            return false;
        }
        Objects.requireNonNull(now, "now");
        Duration leaseTtl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofMinutes(2) : ttl;
        int updated = jdbc.update(
                """
                UPDATE data_export_request
                   SET generation_lease_expires_at = ?, updated_at = ?
                 WHERE request_id = ?
                   AND request_status = ?
                   AND generation_lease_owner = ?
                   AND generation_lease_expires_at IS NOT NULL
                   AND generation_lease_expires_at > ?
                """,
                PlatformTables.timestamp(now.plus(leaseTtl)),
                PlatformTables.timestamp(now),
                requestId.trim(),
                ExportRequestStatus.GENERATING.name(),
                owner.trim(),
                PlatformTables.timestamp(now));
        return updated == 1;
    }

    /**
     * Complete or fail a generation only while this owner still holds a valid lease, then clear the lease.
     * 仅在本持有人仍握有有效租约时完成或失败，并清除租约。
     */
    public boolean completeGeneration(ExportRequest request, String owner, Instant now) {
        Objects.requireNonNull(request, "request");
        if (owner == null || owner.isBlank()) {
            return false;
        }
        Objects.requireNonNull(now, "now");
        int updated = jdbc.update(
                """
                UPDATE data_export_request
                   SET request_status = ?, artifact_object_key = ?, expires_at = ?, updated_at = ?,
                       generation_lease_owner = NULL, generation_lease_expires_at = NULL
                 WHERE request_id = ?
                   AND request_status = ?
                   AND generation_lease_owner = ?
                   AND generation_lease_expires_at IS NOT NULL
                   AND generation_lease_expires_at > ?
                """,
                request.status().name(),
                request.artifactObjectKey(),
                PlatformTables.timestamp(request.expiresAt()),
                PlatformTables.timestamp(now),
                request.requestId(),
                ExportRequestStatus.GENERATING.name(),
                owner.trim(),
                PlatformTables.timestamp(now));
        return updated == 1;
    }

}

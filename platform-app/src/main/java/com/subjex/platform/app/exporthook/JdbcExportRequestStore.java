package com.subjex.platform.app.exporthook;

import com.subjex.platform.app.jdbc.PlatformTables;
import com.subjex.platform.contract.exporthook.ExportRequest;
import com.subjex.platform.contract.exporthook.ExportRequestStatus;
import com.subjex.platform.contract.exporthook.ExportRequesterRealm;
import java.sql.Timestamp;
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
     * Lock one row for the worker so two schedulers do not generate the same artifact.
     * 工作线程锁定一行，避免两个调度实例同时生成同一份产物。
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
                   SET request_status = ?, approver_subject_id = ?, updated_at = ?, decided_at = ?
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
                   SET request_status = ?, artifact_object_key = ?, expires_at = ?, updated_at = ?
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

}

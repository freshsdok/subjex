package com.subjex.platform.app.tenant;

import com.subjex.platform.app.entity.JdbcEntityBlobStore;
import com.subjex.platform.app.jdbc.PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorDirectory;
import com.subjex.platform.app.security.JdbcTenantAdmin;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.support.TenantKeyset;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.tenant.TenantRecord;
import com.subjex.platform.contract.tenant.TenantState;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.ObjectMapper;

/**
 * TenantLifecycleService — TN-2 export ZIP + hard erase with double-confirm (GDPR-ish).
 * <p>
 * Soft-disable ({@link JdbcTenantAdmin#disable}) remains the default off-boarding path. Hard erase
 * requires the tenant already {@code SUSPENDED}, a live challenge token, and refuses {@code platform}.
 * 软禁用仍为默认下线；硬删除须已 SUSPENDED + 有效确认令牌；拒绝保留租户 {@code platform}。
 */
public final class TenantLifecycleService {

    public static final String AUDIT_EXPORT = "tenant.export";
    public static final String AUDIT_ERASE_CHALLENGE = "tenant.erase.challenge";
    public static final String AUDIT_ERASE = "tenant.erase";
    public static final Duration CHALLENGE_TTL = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JdbcTenantAdmin tenants;
    private final ObjectStorage objects;
    private final OperatorActionAudit audit;
    private final ObjectMapper json;
    private final Clock clock;

    public TenantLifecycleService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            JdbcTenantAdmin tenants,
            ObjectStorage objects,
            OperatorActionAudit audit,
            ObjectMapper json,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Validate the tenant can be exported (so the HTTP layer can 400 before starting the body).
     * 校验租户可导出，便于 HTTP 层在开始写响应体之前返回 400。
     */
    public String requireExportable(String tenantId) {
        return requireBusinessTenant(tenantId);
    }

    /**
     * Stream the sync ZIP: JSON tables + blob bytes when ObjectStorage has them.
     * Does not first materialize the archive as a {@code byte[]}.
     * 同步流式 ZIP：表 JSON + 能取到的附件字节；不先把整包放进字节数组。
     */
    public void exportZip(OperatorPrincipal operator, String tenantId, OutputStream output) {
        String tid = requireBusinessTenant(tenantId);
        Objects.requireNonNull(output, "output");
        try {
            writeZip(tid, output);
            audit.record(operator, AUDIT_EXPORT, tid, AuditOutcome.ALLOWED);
        } catch (IOException ex) {
            throw new IllegalStateException("tenant export failed", ex);
        }
    }

    /**
     * Issue one-time erase token (raw token returned once) — 签发一次性擦除令牌（明文只回一次）。
     */
    public EraseChallenge issueEraseChallenge(OperatorPrincipal operator, String tenantId) {
        String tid = requireBusinessTenant(tenantId);
        requireSuspended(tid);
        String challengeId = UUID.randomUUID().toString();
        String rawToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        Instant expires = clock.instant().plus(CHALLENGE_TTL);
        jdbc.update(
                """
                INSERT INTO tenant_erase_challenge
                    (challenge_id, tenant_id, token_hash, created_by_identity_id, expires_at, consumed_at)
                VALUES (?, ?, ?, ?, ?, NULL)
                """,
                challengeId,
                tid,
                sha256(rawToken),
                operator.identityId(),
                PlatformTables.timestamp(expires));
        audit.record(operator, AUDIT_ERASE_CHALLENGE, tid + ":" + challengeId, AuditOutcome.ALLOWED);
        return new EraseChallenge(challengeId, rawToken, expires);
    }

    /**
     * Wipe tenant-scoped rows + blob objects; tenant row stays SUSPENDED —
     * 擦除租户作用域行与附件对象；租户行保持 SUSPENDED。
     */
    public EraseResult erase(OperatorPrincipal operator, String tenantId, String challengeId, String rawToken) {
        String tid = requireBusinessTenant(tenantId);
        requireSuspended(tid);
        consumeChallenge(tid, challengeId, rawToken);
        List<BlobRef> blobs = listBlobs(tid);
        EraseCounts counts = transactions.execute(status -> wipeRows(tid));
        if (counts == null) {
            throw new IllegalStateException("erase transaction returned null");
        }
        int objectsRemoved = 0;
        for (BlobRef blob : blobs) {
            try {
                objects.delete(objectTenant(blob.tenantId()), blob.storageKey());
                objectsRemoved++;
            } catch (RuntimeException ignored) {
                // Best-effort object cleanup after DB wipe — DB 擦除后尽力删对象。
            }
        }
        audit.record(
                operator,
                AUDIT_ERASE,
                tid + ":rows=" + counts.totalRows() + ",objects=" + objectsRemoved,
                AuditOutcome.ALLOWED);
        return new EraseResult(tid, counts, objectsRemoved);
    }

    private void writeZip(String tenantId, OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("tenantId", tenantId);
            manifest.put("exportedAt", clock.instant().toString());
            manifest.put("format", "subjex-tenant-export-v1");
            putJson(zip, "manifest.json", manifest);

            writeTimeKeyedJsonArray(
                    zip,
                    "tasks.json",
                    """
                    SELECT task_id, tenant_id, task_kind, task_state, step_name, attempt_count, max_attempt,
                           model_id, input_digest, human_confirmation, failure_reason, created_at
                    FROM platform_task
                    WHERE tenant_id = ?
                    ORDER BY created_at ASC, task_id ASC
                    LIMIT ?
                    """,
                    """
                    SELECT task_id, tenant_id, task_kind, task_state, step_name, attempt_count, max_attempt,
                           model_id, input_digest, human_confirmation, failure_reason, created_at
                    FROM platform_task
                    WHERE tenant_id = ?
                      AND (created_at > ? OR (created_at = ? AND task_id > ?))
                    ORDER BY created_at ASC, task_id ASC
                    LIMIT ?
                    """,
                    "created_at",
                    "task_id",
                    tenantId);

            writeTimeKeyedJsonArray(
                    zip,
                    "forms.json",
                    """
                    SELECT submission_id, form_key, actor_identity_id, login_name, values_json, result_summary,
                           submitted_at, tenant_id
                    FROM form_submission
                    WHERE tenant_id = ?
                    ORDER BY submitted_at ASC, submission_id ASC
                    LIMIT ?
                    """,
                    """
                    SELECT submission_id, form_key, actor_identity_id, login_name, values_json, result_summary,
                           submitted_at, tenant_id
                    FROM form_submission
                    WHERE tenant_id = ?
                      AND (submitted_at > ? OR (submitted_at = ? AND submission_id > ?))
                    ORDER BY submitted_at ASC, submission_id ASC
                    LIMIT ?
                    """,
                    "submitted_at",
                    "submission_id",
                    tenantId);

            writeTimeKeyedJsonArray(
                    zip,
                    "outbox.json",
                    """
                    SELECT event_id, tenant_id, event_name, event_body, event_state, trace_id, attempt_count,
                           failure_reason, occurred_at, published_at
                    FROM outbox_event
                    WHERE tenant_id = ?
                    ORDER BY occurred_at ASC, event_id ASC
                    LIMIT ?
                    """,
                    """
                    SELECT event_id, tenant_id, event_name, event_body, event_state, trace_id, attempt_count,
                           failure_reason, occurred_at, published_at
                    FROM outbox_event
                    WHERE tenant_id = ?
                      AND (occurred_at > ? OR (occurred_at = ? AND event_id > ?))
                    ORDER BY occurred_at ASC, event_id ASC
                    LIMIT ?
                    """,
                    "occurred_at",
                    "event_id",
                    tenantId);

            writeNamedJsonArray(
                    zip,
                    "hosts.json",
                    """
                    SELECT host_name, tenant_id, created_at, created_by_identity_id
                    FROM tenant_host
                    WHERE tenant_id = ?
                    ORDER BY host_name ASC
                    LIMIT ?
                    """,
                    """
                    SELECT host_name, tenant_id, created_at, created_by_identity_id
                    FROM tenant_host
                    WHERE tenant_id = ? AND host_name > ?
                    ORDER BY host_name ASC
                    LIMIT ?
                    """,
                    "host_name",
                    tenantId);

            writeCompositeKeyedJsonArray(
                    zip,
                    "entity_records.json",
                    """
                    SELECT tenant_id, entity_key, record_id, record_state, attrs, created_at, updated_at
                    FROM entity_record
                    WHERE tenant_id = ?
                    ORDER BY entity_key ASC, record_id ASC
                    LIMIT ?
                    """,
                    """
                    SELECT tenant_id, entity_key, record_id, record_state, attrs, created_at, updated_at
                    FROM entity_record
                    WHERE tenant_id = ?
                      AND (entity_key > ? OR (entity_key = ? AND record_id > ?))
                    ORDER BY entity_key ASC, record_id ASC
                    LIMIT ?
                    """,
                    "entity_key",
                    "record_id",
                    tenantId);

            writeTimeKeyedJsonArray(
                    zip,
                    "entity_blobs.json",
                    """
                    SELECT blob_id, tenant_id, entity_key, record_id, field_name, content_type, byte_size,
                           storage_key, checksum_sha256, created_at
                    FROM entity_blob
                    WHERE tenant_id = ?
                    ORDER BY created_at ASC, blob_id ASC
                    LIMIT ?
                    """,
                    """
                    SELECT blob_id, tenant_id, entity_key, record_id, field_name, content_type, byte_size,
                           storage_key, checksum_sha256, created_at
                    FROM entity_blob
                    WHERE tenant_id = ?
                      AND (created_at > ? OR (created_at = ? AND blob_id > ?))
                    ORDER BY created_at ASC, blob_id ASC
                    LIMIT ?
                    """,
                    "created_at",
                    "blob_id",
                    tenantId);

            writeBlobBytes(zip, tenantId);
            zip.finish();
            zip.flush();
        }
    }

    private void writeBlobBytes(ZipOutputStream zip, String tenantId) throws IOException {
        Instant afterTime = Instant.EPOCH;
        String afterId = "";
        int pageSize = TenantKeyset.EXPORT_PAGE_LIMIT;
        while (true) {
            List<BlobExportRow> page = jdbc.query(
                    """
                    SELECT blob_id, tenant_id, storage_key, created_at
                    FROM entity_blob
                    WHERE tenant_id = ?
                      AND (created_at > ? OR (created_at = ? AND blob_id > ?))
                    ORDER BY created_at ASC, blob_id ASC
                    LIMIT ?
                    """,
                    (rs, n) -> new BlobExportRow(
                            rs.getString("blob_id"),
                            rs.getString("tenant_id"),
                            rs.getString("storage_key"),
                            rs.getTimestamp("created_at").toInstant()),
                    tenantId,
                    java.sql.Timestamp.from(afterTime),
                    java.sql.Timestamp.from(afterTime),
                    afterId,
                    pageSize);
            for (BlobExportRow row : page) {
                objects.find(objectTenant(row.tenantId()), row.storageKey()).ifPresent(stored -> {
                    try {
                        zip.putNextEntry(new ZipEntry("blobs/" + row.blobId()));
                        zip.write(stored.content());
                        zip.closeEntry();
                    } catch (IOException ex) {
                        throw new IllegalStateException("blob zip failed: " + row.blobId(), ex);
                    }
                });
                afterTime = row.createdAt();
                afterId = row.blobId();
            }
            if (page.size() < pageSize) {
                break;
            }
        }
    }

    private EraseCounts wipeRows(String tenantId) {
        int blobs = jdbc.update("DELETE FROM entity_blob WHERE tenant_id = ?", tenantId);
        int entities = jdbc.update("DELETE FROM entity_record WHERE tenant_id = ?", tenantId);
        int forms = jdbc.update("DELETE FROM form_submission WHERE tenant_id = ?", tenantId);
        int tasks = jdbc.update("DELETE FROM platform_task WHERE tenant_id = ?", tenantId);
        int outbox = jdbc.update("DELETE FROM outbox_event WHERE tenant_id = ?", tenantId);
        int quota = jdbc.update("DELETE FROM tenant_quota_override WHERE tenant_id = ?", tenantId);
        int oauth = jdbc.update("DELETE FROM oauth_client WHERE tenant_id = ?", tenantId);
        int drafts = jdbc.update("DELETE FROM declaration_revision WHERE tenant_id = ?", tenantId);
        int promote = jdbc.update("DELETE FROM declaration_promote WHERE tenant_id = ?", tenantId);
        int migration = jdbc.update("DELETE FROM declaration_migration WHERE tenant_id = ?", tenantId);
        int approval = jdbc.update("DELETE FROM declaration_promote_approval WHERE tenant_id = ?", tenantId);
        int tenantOrg = jdbc.update("DELETE FROM tenant_organization WHERE tenant_id = ?", tenantId);
        int grants = jdbc.update("DELETE FROM operator_tenant_grant WHERE tenant_id = ?", tenantId);
        int challenges = jdbc.update("DELETE FROM tenant_erase_challenge WHERE tenant_id = ?", tenantId);
        int hosts = jdbc.update("DELETE FROM tenant_host WHERE tenant_id = ?", tenantId);
        return new EraseCounts(
                tasks, forms, outbox, entities, blobs, quota, oauth, drafts, promote, migration, approval, tenantOrg, grants, challenges, hosts);
    }

    private void consumeChallenge(String tenantId, String challengeId, String rawToken) {
        if (challengeId == null || challengeId.isBlank() || rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("challengeId and token required");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT token_hash, expires_at, consumed_at FROM tenant_erase_challenge
                WHERE challenge_id = ? AND tenant_id = ?
                """,
                challengeId.trim(),
                tenantId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("erase challenge missing");
        }
        Map<String, Object> row = rows.get(0);
        if (row.get("consumed_at") != null) {
            throw new IllegalArgumentException("erase challenge already consumed");
        }
        Instant expires = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
        if (!expires.isAfter(clock.instant())) {
            throw new IllegalArgumentException("erase challenge expired");
        }
        if (!sha256(rawToken).equals(String.valueOf(row.get("token_hash")))) {
            throw new IllegalArgumentException("erase token mismatch");
        }
        int updated = jdbc.update(
                """
                UPDATE tenant_erase_challenge SET consumed_at = ?
                WHERE challenge_id = ? AND consumed_at IS NULL
                """,
                PlatformTables.timestamp(clock.instant()),
                challengeId.trim());
        if (updated != 1) {
            throw new IllegalArgumentException("erase challenge already consumed");
        }
    }

    private List<BlobRef> listBlobs(String tenantId) {
        return jdbc.query(
                "SELECT tenant_id, storage_key FROM entity_blob WHERE tenant_id = ?",
                (rs, n) -> new BlobRef(rs.getString("tenant_id"), rs.getString("storage_key")),
                tenantId);
    }

    private String requireBusinessTenant(String tenantId) {
        TenantRecord row = tenants.require(tenantId);
        if (JdbcOperatorDirectory.OPERATOR_TENANT.equals(row.tenantId())) {
            throw new IllegalArgumentException("cannot export or erase reserved tenant: platform");
        }
        return row.tenantId();
    }

    private void requireSuspended(String tenantId) {
        TenantRecord row = tenants.require(tenantId);
        if (row.tenantState() != TenantState.SUSPENDED) {
            throw new IllegalArgumentException("tenant must be soft-disabled (SUSPENDED) before hard erase");
        }
    }

    private void writeTimeKeyedJsonArray(
            ZipOutputStream zip,
            String entryName,
            String firstSql,
            String nextSql,
            String timeColumn,
            String idColumn,
            String tenantId)
            throws IOException {
        int pageSize = TenantKeyset.EXPORT_PAGE_LIMIT;
        zip.putNextEntry(new ZipEntry(entryName));
        JsonGenerator generator = jsonArrayGenerator(zip);
        try {
            Instant afterTime = Instant.EPOCH;
            String afterId = "";
            boolean first = true;
            while (true) {
                List<Map<String, Object>> page;
                if (first) {
                    page = jdbc.queryForList(firstSql, tenantId, pageSize);
                    first = false;
                } else {
                    java.sql.Timestamp ts = java.sql.Timestamp.from(afterTime);
                    page = jdbc.queryForList(nextSql, tenantId, ts, ts, afterId, pageSize);
                }
                for (Map<String, Object> row : page) {
                    afterTime = timestampOf(row, timeColumn);
                    afterId = stringOf(row, idColumn);
                    generator.writePOJO(normalizeRow(row));
                }
                if (page.size() < pageSize) {
                    break;
                }
            }
            generator.writeEndArray();
            generator.flush();
        } finally {
            generator.close();
        }
        zip.closeEntry();
        zip.flush();
    }

    private void writeNamedJsonArray(
            ZipOutputStream zip, String entryName, String firstSql, String nextSql, String nameColumn, String tenantId)
            throws IOException {
        int pageSize = TenantKeyset.EXPORT_PAGE_LIMIT;
        zip.putNextEntry(new ZipEntry(entryName));
        JsonGenerator generator = jsonArrayGenerator(zip);
        try {
            String afterName = "";
            boolean first = true;
            while (true) {
                List<Map<String, Object>> page;
                if (first) {
                    page = jdbc.queryForList(firstSql, tenantId, pageSize);
                    first = false;
                } else {
                    page = jdbc.queryForList(nextSql, tenantId, afterName, pageSize);
                }
                for (Map<String, Object> row : page) {
                    afterName = stringOf(row, nameColumn);
                    generator.writePOJO(normalizeRow(row));
                }
                if (page.size() < pageSize) {
                    break;
                }
            }
            generator.writeEndArray();
            generator.flush();
        } finally {
            generator.close();
        }
        zip.closeEntry();
        zip.flush();
    }

    private void writeCompositeKeyedJsonArray(
            ZipOutputStream zip,
            String entryName,
            String firstSql,
            String nextSql,
            String firstColumn,
            String secondColumn,
            String tenantId)
            throws IOException {
        int pageSize = TenantKeyset.EXPORT_PAGE_LIMIT;
        zip.putNextEntry(new ZipEntry(entryName));
        JsonGenerator generator = jsonArrayGenerator(zip);
        try {
            String afterFirst = "";
            String afterSecond = "";
            boolean first = true;
            while (true) {
                List<Map<String, Object>> page;
                if (first) {
                    page = jdbc.queryForList(firstSql, tenantId, pageSize);
                    first = false;
                } else {
                    page = jdbc.queryForList(nextSql, tenantId, afterFirst, afterFirst, afterSecond, pageSize);
                }
                for (Map<String, Object> row : page) {
                    afterFirst = stringOf(row, firstColumn);
                    afterSecond = stringOf(row, secondColumn);
                    generator.writePOJO(normalizeRow(row));
                }
                if (page.size() < pageSize) {
                    break;
                }
            }
            generator.writeEndArray();
            generator.flush();
        } finally {
            generator.close();
        }
        zip.closeEntry();
        zip.flush();
    }

    private JsonGenerator jsonArrayGenerator(ZipOutputStream zip) throws IOException {
        // Do not close the ZIP when a JSON entry finishes — 单个 JSON 条目结束时不要关掉整个 ZIP。
        JsonGenerator generator = json.createGenerator(zip);
        generator.configure(StreamWriteFeature.AUTO_CLOSE_TARGET, false);
        generator.writeStartArray();
        return generator;
    }

    private Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            Object v = e.getValue();
            if (v instanceof java.sql.Timestamp ts) {
                copy.put(e.getKey(), ts.toInstant().toString());
            } else {
                copy.put(e.getKey(), v);
            }
        }
        return copy;
    }


    private Instant timestampOf(Map<String, Object> row, String column) {
        Object value = valueOf(row, column);
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return Instant.parse(String.valueOf(value));
    }

    private static String stringOf(Map<String, Object> row, String column) {
        return String.valueOf(valueOf(row, column));
    }

    private static Object valueOf(Map<String, Object> row, String column) {
        if (row.containsKey(column)) {
            return row.get(column);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        throw new IllegalStateException("missing export column: " + column);
    }

    private void putJson(ZipOutputStream zip, String name, Object value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        JsonGenerator generator = json.createGenerator(zip);
        generator.configure(StreamWriteFeature.AUTO_CLOSE_TARGET, false);
        try {
            generator.writePOJO(value);
            generator.flush();
        } finally {
            generator.close();
        }
        zip.closeEntry();
        zip.flush();
    }

    private static String objectTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return JdbcEntityBlobStore.OBJECT_TENANT_PLATFORM;
        }
        return tenantId.trim();
    }

    private static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 required", ex);
        }
    }

    private record BlobRef(String tenantId, String storageKey) {}

    private record BlobExportRow(String blobId, String tenantId, String storageKey, Instant createdAt) {}

    public record EraseChallenge(String challengeId, String token, Instant expiresAt) {}

    public record EraseCounts(
            int tasks,
            int forms,
            int outbox,
            int entityRecords,
            int entityBlobs,
            int quotaOverrides,
            int oauthClients,
            int declarationDrafts,
            int declarationPromotes,
            int declarationMigrations,
            int declarationApprovals,
            int tenantOrganizations,
            int operatorGrants,
            int eraseChallenges,
            int tenantHosts) {
        public int totalRows() {
            return tasks
                    + forms
                    + outbox
                    + entityRecords
                    + entityBlobs
                    + quotaOverrides
                    + oauthClients
                    + declarationDrafts
                    + declarationPromotes
                    + declarationMigrations
                    + declarationApprovals
                    + tenantOrganizations
                    + operatorGrants
                    + eraseChallenges
                    + tenantHosts;
        }
    }

    public record EraseResult(String tenantId, EraseCounts counts, int objectStorageDeletes) {}
}

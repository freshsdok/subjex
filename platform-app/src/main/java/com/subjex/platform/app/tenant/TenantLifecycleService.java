package com.subjex.platform.app.tenant;

import com.subjex.platform.app.entity.JdbcEntityBlobStore;
import com.subjex.platform.app.jdbc.PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorDirectory;
import com.subjex.platform.app.security.JdbcTenantAdmin;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.tenant.TenantRecord;
import com.subjex.platform.contract.tenant.TenantState;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
     * Sync ZIP: JSON tables + blob bytes when ObjectStorage has them —
     * 同步 ZIP：表 JSON + 能取到的附件字节。
     */
    public byte[] exportZip(OperatorPrincipal operator, String tenantId) {
        String tid = requireBusinessTenant(tenantId);
        try {
            byte[] zip = buildZip(tid);
            audit.record(operator, AUDIT_EXPORT, tid, AuditOutcome.ALLOWED);
            return zip;
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

    private byte[] buildZip(String tenantId) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("tenantId", tenantId);
            manifest.put("exportedAt", clock.instant().toString());
            manifest.put("format", "subjex-tenant-export-v1");
            putJson(zip, "manifest.json", manifest);

            List<Map<String, Object>> tasks = queryMaps(
                    """
                    SELECT task_id, tenant_id, task_kind, task_state, step_name, attempt_count, max_attempt,
                           model_id, input_digest, human_confirmation, failure_reason, created_at
                    FROM platform_task WHERE tenant_id = ? ORDER BY created_at, task_id
                    """,
                    tenantId);
            putJson(zip, "tasks.json", tasks);

            List<Map<String, Object>> forms = queryMaps(
                    """
                    SELECT submission_id, form_key, actor_identity_id, login_name, values_json, result_summary,
                           submitted_at, tenant_id
                    FROM form_submission WHERE tenant_id = ? ORDER BY submitted_at, submission_id
                    """,
                    tenantId);
            putJson(zip, "forms.json", forms);

            List<Map<String, Object>> outbox = queryMaps(
                    """
                    SELECT event_id, tenant_id, event_name, event_body, event_state, trace_id, attempt_count,
                           failure_reason, occurred_at, published_at
                    FROM outbox_event WHERE tenant_id = ? ORDER BY occurred_at, event_id
                    """,
                    tenantId);
            putJson(zip, "outbox.json", outbox);

            List<Map<String, Object>> entities = queryMaps(
                    """
                    SELECT tenant_id, entity_key, record_id, record_state, attrs, created_at, updated_at
                    FROM entity_record WHERE tenant_id = ? ORDER BY entity_key, record_id
                    """,
                    tenantId);
            putJson(zip, "entity_records.json", entities);

            List<Map<String, Object>> blobMeta = queryMaps(
                    """
                    SELECT blob_id, tenant_id, entity_key, record_id, field_name, content_type, byte_size,
                           storage_key, checksum_sha256, created_at
                    FROM entity_blob WHERE tenant_id = ? ORDER BY created_at, blob_id
                    """,
                    tenantId);
            putJson(zip, "entity_blobs.json", blobMeta);

            for (Map<String, Object> row : blobMeta) {
                String blobId = String.valueOf(row.get("blob_id"));
                String storageKey = String.valueOf(row.get("storage_key"));
                String rowTenant = String.valueOf(row.get("tenant_id"));
                objects.find(objectTenant(rowTenant), storageKey).ifPresent(stored -> {
                    try {
                        zip.putNextEntry(new ZipEntry("blobs/" + blobId));
                        zip.write(stored.content());
                        zip.closeEntry();
                    } catch (IOException ex) {
                        throw new IllegalStateException("blob zip failed: " + blobId, ex);
                    }
                });
            }
        }
        return bytes.toByteArray();
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
        return new EraseCounts(
                tasks, forms, outbox, entities, blobs, quota, oauth, drafts, promote, migration, approval, tenantOrg, grants, challenges);
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

    private List<Map<String, Object>> queryMaps(String sql, String tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, tenantId);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                Object v = e.getValue();
                if (v instanceof java.sql.Timestamp ts) {
                    copy.put(e.getKey(), ts.toInstant().toString());
                } else {
                    copy.put(e.getKey(), v);
                }
            }
            out.add(copy);
        }
        return out;
    }

    private void putJson(ZipOutputStream zip, String name, Object value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(json.writeValueAsBytes(value));
        zip.closeEntry();
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
            int eraseChallenges) {
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
                    + eraseChallenges;
        }
    }

    public record EraseResult(String tenantId, EraseCounts counts, int objectStorageDeletes) {}
}

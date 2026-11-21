package com.subjex.platform.app.storage;

import com.subjex.platform.app.jdbc.PlatformTables;
import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.exporthook.SensitivityClass;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcObjectGrantStore — JDBC {@code object_descriptor} / {@code object_grant} (ADR 0015 / WP3-1).
 * <p>
 * Tenant must be an existing ACTIVE business tenant (never reserved {@code platform}).
 * Organization grants require an ACTIVE {@code tenant_organization} link at write time.
 * 租户必须是已存在的 ACTIVE 业务租户（不得为 platform）。组织授权在写入时须有 ACTIVE 租户-组织关联。
 */
public final class JdbcObjectGrantStore implements ObjectGrantStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectAccessAudit audit;

    private static final RowMapper<ObjectDescriptor> DESCRIPTOR = (row, n) -> new ObjectDescriptor(
            row.getString("object_id"),
            row.getString("tenant_id"),
            row.getString("object_key"),
            row.getString("owner_subject_id"),
            instant(row, "created_at"));

    private static final RowMapper<ObjectGrant> GRANT = (row, n) -> new ObjectGrant(
            row.getString("grant_id"),
            row.getString("tenant_id"),
            row.getString("object_key"),
            ObjectGrantKind.valueOf(row.getString("grantee_kind")),
            row.getString("grantee_id"),
            instant(row, "expires_at"),
            instant(row, "created_at"),
            row.getString("created_by_subject_id"));

    public JdbcObjectGrantStore(JdbcTemplate jdbc, Clock clock) {
        this(jdbc, clock, null);
    }

    public JdbcObjectGrantStore(JdbcTemplate jdbc, Clock clock, ObjectAccessAudit audit) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.audit = audit;
    }

    @Override
    public ObjectDescriptor register(String tenantId, String objectKey, String ownerSubjectId) {
        String tid = requireActiveBusinessTenant(tenantId);
        String key = requireObjectKey(objectKey);
        String owner = requireToken(ownerSubjectId, "ownerSubjectId");
        Optional<ObjectDescriptor> existing = find(tid, key);
        if (existing.isPresent()) {
            if (owner.equals(existing.get().ownerSubjectId())) {
                return existing.get();
            }
            throw new IllegalArgumentException("object already registered");
        }
        Instant now = clock.instant();
        ObjectDescriptor row = new ObjectDescriptor(UUID.randomUUID().toString(), tid, key, owner, now);
        jdbc.update(
                """
                INSERT INTO object_descriptor
                  (object_id, tenant_id, object_key, owner_subject_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                row.objectId(),
                row.tenantId(),
                row.objectKey(),
                row.ownerSubjectId(),
                PlatformTables.timestamp(now));
        return row;
    }

    @Override
    public Optional<ObjectDescriptor> find(String tenantId, String objectKey) {
        if (tenantId == null || tenantId.isBlank() || objectKey == null || objectKey.isBlank()) {
            return Optional.empty();
        }
        List<ObjectDescriptor> rows = jdbc.query(
                """
                SELECT object_id, tenant_id, object_key, owner_subject_id, created_at
                FROM object_descriptor
                WHERE tenant_id = ? AND object_key = ?
                """,
                DESCRIPTOR,
                tenantId.trim(),
                objectKey.trim());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public ObjectGrant grantToSubject(
            String tenantId, String objectKey, String granteeSubjectId, Instant expiresAt, String createdBySubjectId) {
        ObjectGrant grant = upsertGrant(
                tenantId, objectKey, ObjectGrantKind.SUBJECT, requireToken(granteeSubjectId, "granteeSubjectId"),
                expiresAt, createdBySubjectId);
        recordGrantCreate(grant, createdBySubjectId);
        return grant;
    }

    @Override
    public ObjectGrant grantToOrganization(
            String tenantId, String objectKey, String organizationId, Instant expiresAt, String createdBySubjectId) {
        String org = requireToken(organizationId, "organizationId");
        String tid = requireActiveBusinessTenant(tenantId);
        requireOrganizationBoundToTenant(tid, org);
        ObjectGrant grant = upsertGrant(tid, objectKey, ObjectGrantKind.ORGANIZATION, org, expiresAt, createdBySubjectId);
        recordGrantCreate(grant, createdBySubjectId);
        return grant;
    }

    @Override
    public void revoke(String grantId) {
        revoke(grantId, null);
    }

    /**
     * Revoke a grant and audit the actor when known — 撤销授权，并在可知时把操作者记入审计。
     */
    public void revoke(String grantId, String actorSubjectId) {
        if (grantId == null || grantId.isBlank()) {
            return;
        }
        String id = grantId.trim();
        List<ObjectGrant> existing = jdbc.query(
                """
                SELECT grant_id, tenant_id, object_key, grantee_kind, grantee_id,
                       expires_at, created_at, created_by_subject_id
                FROM object_grant
                WHERE grant_id = ?
                """,
                GRANT,
                id);
        int deleted = jdbc.update("DELETE FROM object_grant WHERE grant_id = ?", id);
        if (deleted > 0 && !existing.isEmpty() && audit != null) {
            ObjectGrant row = existing.get(0);
            String actor = actorSubjectId == null || actorSubjectId.isBlank()
                    ? row.createdBySubjectId()
                    : actorSubjectId;
            audit.recordGrantRevoke(row.tenantId(), actor, row.objectKey(), row.granteeKind(), row.granteeId(), row.grantId());
        }
    }

    @Override
    public List<ObjectGrant> listGrants(String tenantId, String objectKey) {
        if (tenantId == null || tenantId.isBlank() || objectKey == null || objectKey.isBlank()) {
            return List.of();
        }
        return jdbc.query(
                """
                SELECT grant_id, tenant_id, object_key, grantee_kind, grantee_id,
                       expires_at, created_at, created_by_subject_id
                FROM object_grant
                WHERE tenant_id = ? AND object_key = ?
                ORDER BY created_at, grant_id
                """,
                GRANT,
                tenantId.trim(),
                objectKey.trim());
    }

    @Override
    public List<ObjectDescriptor> listOwnedOrSubjectGranted(
            String tenantId, String subjectId, int limit, int offset) {
        String tid = ObjectGrantStore.requireExplicitBusinessTenantId(tenantId);
        if (subjectId == null || subjectId.isBlank()) {
            return List.of();
        }
        int lim = limit <= 0 ? 50 : Math.min(limit, 200);
        int off = Math.max(offset, 0);
        String sql =
                "SELECT object_id, tenant_id, object_key, owner_subject_id, created_at "
                        + "FROM object_descriptor "
                        + "WHERE tenant_id = ? AND owner_subject_id = ? "
                        + "UNION "
                        + "SELECT d.object_id, d.tenant_id, d.object_key, d.owner_subject_id, d.created_at "
                        + "FROM object_descriptor d "
                        + "INNER JOIN object_grant g "
                        + "  ON g.tenant_id = d.tenant_id AND g.object_key = d.object_key "
                        + "WHERE d.tenant_id = ? "
                        + "  AND g.grantee_kind = ? "
                        + "  AND g.grantee_id = ? "
                        + "  AND (g.expires_at IS NULL OR g.expires_at > ?) "
                        + "ORDER BY created_at DESC, object_id "
                        + "LIMIT ? OFFSET ?";
        return jdbc.query(
                sql,
                DESCRIPTOR,
                tid,
                subjectId.trim(),
                tid,
                ObjectGrantKind.SUBJECT.name(),
                subjectId.trim(),
                PlatformTables.timestamp(clock.instant()),
                lim,
                off);
    }

    private ObjectGrant upsertGrant(
            String tenantId,
            String objectKey,
            ObjectGrantKind kind,
            String granteeId,
            Instant expiresAt,
            String createdBySubjectId) {
        String tid = requireActiveBusinessTenant(tenantId);
        String key = requireObjectKey(objectKey);
        if (find(tid, key).isEmpty()) {
            throw new IllegalArgumentException("object is not registered in tenant");
        }
        Timestamp expires = expiresAt == null ? null : PlatformTables.timestamp(expiresAt);
        String createdBy = createdBySubjectId == null || createdBySubjectId.isBlank()
                ? null
                : createdBySubjectId.trim();
        List<ObjectGrant> existing = jdbc.query(
                """
                SELECT grant_id, tenant_id, object_key, grantee_kind, grantee_id,
                       expires_at, created_at, created_by_subject_id
                FROM object_grant
                WHERE tenant_id = ? AND object_key = ? AND grantee_kind = ? AND grantee_id = ?
                """,
                GRANT,
                tid,
                key,
                kind.name(),
                granteeId);
        if (!existing.isEmpty()) {
            ObjectGrant row = existing.get(0);
            jdbc.update(
                    "UPDATE object_grant SET expires_at = ? WHERE grant_id = ?",
                    expires,
                    row.grantId());
            return new ObjectGrant(
                    row.grantId(),
                    row.tenantId(),
                    row.objectKey(),
                    row.granteeKind(),
                    row.granteeId(),
                    expiresAt,
                    row.createdAt(),
                    row.createdBySubjectId());
        }
        Instant now = clock.instant();
        ObjectGrant row = new ObjectGrant(
                UUID.randomUUID().toString(), tid, key, kind, granteeId, expiresAt, now, createdBy);
        jdbc.update(
                """
                INSERT INTO object_grant
                  (grant_id, tenant_id, object_key, grantee_kind, grantee_id,
                   expires_at, created_at, created_by_subject_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.grantId(),
                row.tenantId(),
                row.objectKey(),
                kind.name(),
                row.granteeId(),
                expires,
                PlatformTables.timestamp(now),
                createdBy);
        return row;
    }

    /**
     * Tenant must exist, be ACTIVE, and not be the reserved operator tenant.
     * 租户必须已存在、为 ACTIVE，且不是保留操作员租户。
     */
    String requireActiveBusinessTenant(String tenantId) {
        String id = ObjectGrantStore.requireExplicitBusinessTenantId(tenantId);
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
     * Organization must have an ACTIVE TenantOrganization link in this tenant.
     * 组织必须在该租户有 ACTIVE 的租户-组织关联。
     */
    private void requireOrganizationBoundToTenant(String tenantId, String organizationId) {
        List<String> links = jdbc.query(
                """
                SELECT link_state FROM tenant_organization
                WHERE tenant_id = ? AND organization_id = ?
                """,
                (row, n) -> row.getString("link_state"),
                tenantId,
                organizationId);
        if (links.isEmpty()) {
            throw new IllegalArgumentException("organization is not bound to tenant");
        }
        String state = links.get(0) == null ? "" : links.get(0).trim().toUpperCase(Locale.ROOT);
        if (!JdbcOrganizationStore.STATE_ACTIVE.equals(state)) {
            throw new IllegalArgumentException("organization is not bound to tenant");
        }
    }

    private static Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        Timestamp timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    static String requireObjectKey(String objectKey) {
        String key = requireToken(objectKey, "objectKey");
        if (key.contains("..") || key.indexOf('/') >= 0 || key.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("object key must be a single name");
        }
        return key;
    }

    private static String requireToken(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " required");
        }
        return value.trim();
    }

    private void recordGrantCreate(ObjectGrant grant, String createdBySubjectId) {
        if (audit == null || grant == null) {
            return;
        }
        String actor = createdBySubjectId == null || createdBySubjectId.isBlank()
                ? grant.createdBySubjectId()
                : createdBySubjectId;
        audit.recordGrantCreate(grant.tenantId(), actor, grant.objectKey(), grant.granteeKind(), grant.granteeId());
    }


    @Override
    public SensitivityClass objectSensitivity(String tenantId, String objectKey) {
        String tid = requireActiveBusinessTenant(tenantId);
        String key = requireObjectKey(objectKey);
        List<String> rows = jdbc.query(
                "SELECT sensitivity FROM object_descriptor WHERE tenant_id = ? AND object_key = ?",
                (row, n) -> row.getString("sensitivity"),
                tid,
                key);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("object not found");
        }
        return SensitivityClass.parse(rows.get(0));
    }

    @Override
    public void setObjectSensitivity(String tenantId, String objectKey, SensitivityClass sensitivity) {
        String tid = requireActiveBusinessTenant(tenantId);
        String key = requireObjectKey(objectKey);
        SensitivityClass value = sensitivity == null ? SensitivityClass.NORMAL : sensitivity;
        int updated = jdbc.update(
                "UPDATE object_descriptor SET sensitivity = ? WHERE tenant_id = ? AND object_key = ?",
                value.name(),
                tid,
                key);
        if (updated == 0) {
            throw new IllegalArgumentException("object not found");
        }
    }

}

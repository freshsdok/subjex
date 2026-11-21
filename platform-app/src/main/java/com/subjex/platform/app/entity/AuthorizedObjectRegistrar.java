package com.subjex.platform.app.entity;

import com.subjex.platform.app.storage.ObjectAccessAudit;
import com.subjex.platform.app.storage.ObjectContentTypes;
import com.subjex.platform.app.storage.ObjectGrantStore;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.util.Objects;

/**
 * AuthorizedObjectRegistrar — MIME-check, put, register, and audit a new authorized-track object (WP3-4).
 * 授权轨新对象：校验 MIME、写入字节、登记所有者并记审计。不回填既有 entity_blob。
 */
public final class AuthorizedObjectRegistrar {

    private final EntityBlobStore blobs;
    private final ObjectGrantStore grants;
    private final ObjectAccessAudit audit;

    public AuthorizedObjectRegistrar(EntityBlobStore blobs, ObjectGrantStore grants, ObjectAccessAudit audit) {
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /**
     * Store bytes only after the shared MIME allowlist accepts the type; then register and audit.
     * A rejected type is not stored; a refused audit row may still be written.
     * 仅在共用 MIME 白名单通过后存字节，再登记并审计。拒绝的类型不会落盘；仍可记拒绝审计。
     */
    public EntityBlobMetadata putRegistered(
            String tenantId,
            String entityKey,
            String recordId,
            String fieldName,
            byte[] content,
            String contentType,
            String ownerSubjectId,
            String actorIdentityId) {
        String objectTenant = JdbcEntityBlobStore.objectTenant(tenantId);
        if (!EntityBlobEndpoint.isAuthorizedTrackTenant(objectTenant)) {
            throw new IllegalArgumentException("explicit business tenant is required");
        }
        String type;
        try {
            type = ObjectContentTypes.requireAllowed(contentType);
        } catch (IllegalArgumentException ex) {
            audit.recordUpload(
                    objectTenant,
                    actorIdentityId,
                    "contentType=" + (contentType == null ? "" : contentType),
                    entityKey,
                    AuditOutcome.REFUSED);
            throw ex;
        }
        EntityBlobMetadata meta = blobs.put(tenantId, entityKey, recordId, fieldName, content, type);
        try {
            grants.register(objectTenant, meta.storageKey(), ownerSubjectId);
        } catch (RuntimeException ex) {
            blobs.delete(meta.tenantId(), meta.blobId());
            throw ex;
        }
        audit.recordUpload(objectTenant, actorIdentityId, meta.storageKey(), meta.entityKey(), AuditOutcome.ALLOWED);
        return meta;
    }
}

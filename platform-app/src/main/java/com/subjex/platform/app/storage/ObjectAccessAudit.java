package com.subjex.platform.app.storage;

import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.audit.AuditPort;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * ObjectAccessAudit — records object upload, signed-link issue, and grant changes on the existing {@link AuditPort}.
 * 把对象上传、签名下载签发、授权变更记到既有审计端口（ADR 0015 D5 / WP3-4）。不另开审计产品。
 * <p>
 * Tenant is required and is never defaulted to reserved {@code platform}. Query stays {@code GET /api/v1/audit}.
 * 必须给出租户，永不默认 platform。查询仍走既有审计接口。
 */
public final class ObjectAccessAudit implements ObjectDownloadIssueAuditHook {

    /** Object bytes committed / registered — 对象字节已提交并登记。 */
    public static final String UPLOAD = "object.upload";

    /** Signed download URL authorized and issued — 已授权并签发签名下载。 */
    public static final String DOWNLOAD_ISSUE = "object.download.issue";

    /** Grant created or refreshed — 授权创建或更新。 */
    public static final String GRANT_CREATE = "object.grant.create";

    /** Grant revoked — 授权撤销。 */
    public static final String GRANT_REVOKE = "object.grant.revoke";

    private final AuditPort auditPort;
    private final Clock clock;

    public ObjectAccessAudit(AuditPort auditPort, Clock clock) {
        this.auditPort = Objects.requireNonNull(auditPort, "auditPort");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public void recordUpload(
            String tenantId, String actorId, String actionTarget, String entityKey, AuditOutcome outcome) {
        record(tenantId, actorId, UPLOAD, actionTarget, entityKey, outcome);
    }

    @Override
    public void afterAuthorizedIssue(String tenantId, String objectKey, String actorSubjectId, Instant expiresAt) {
        String target = objectKey == null ? "" : objectKey.trim();
        if (expiresAt != null && !target.isEmpty()) {
            target = target + " expiresAt=" + expiresAt;
        }
        record(tenantId, actorSubjectId, DOWNLOAD_ISSUE, target, null, AuditOutcome.ALLOWED);
    }

    @Override
    public void afterDeniedIssue(String tenantId, String objectKey, String actorSubjectId) {
        record(tenantId, actorSubjectId, DOWNLOAD_ISSUE, objectKey, null, AuditOutcome.REFUSED);
    }

    public void recordGrantCreate(
            String tenantId, String actorId, String objectKey, ObjectGrantKind kind, String granteeId) {
        record(
                tenantId,
                actorId,
                GRANT_CREATE,
                target(objectKey, kind, granteeId, null),
                null,
                AuditOutcome.ALLOWED);
    }

    public void recordGrantRevoke(
            String tenantId, String actorId, String objectKey, ObjectGrantKind kind, String granteeId, String grantId) {
        record(
                tenantId,
                actorId,
                GRANT_REVOKE,
                target(objectKey, kind, granteeId, grantId),
                null,
                AuditOutcome.ALLOWED);
    }

    void record(
            String tenantId,
            String actorId,
            String actionName,
            String actionTarget,
            String entityKey,
            AuditOutcome outcome) {
        String tid = requireTenant(tenantId);
        Objects.requireNonNull(outcome, "outcome");
        String actor = actorId == null || actorId.isBlank() ? "" : actorId.trim();
        auditPort.record(new AuditEntry(
                UUID.randomUUID().toString(),
                tid,
                actor,
                actionName,
                actionTarget == null ? "" : actionTarget,
                outcome,
                clock.instant(),
                blankToNull(entityKey),
                null,
                null));
    }

    /**
     * Fail closed: never default a missing tenant to {@code platform}.
     * 失败关闭：缺失租户绝不默认写入 platform。
     */
    static String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("audit tenantId is required");
        }
        return tenantId.trim();
    }

    static String target(String objectKey, ObjectGrantKind kind, String granteeId, String grantId) {
        StringBuilder sb = new StringBuilder();
        sb.append(objectKey == null ? "" : objectKey.trim());
        sb.append(':').append(kind == null ? "" : kind.name());
        sb.append(':').append(granteeId == null ? "" : granteeId.trim());
        if (grantId != null && !grantId.isBlank()) {
            sb.append(':').append(grantId.trim());
        }
        return sb.toString();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

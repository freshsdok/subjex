package com.subjex.platform.app.storage;

import java.time.Instant;

/**
 * ObjectDownloadIssueAuditHook — called when a signed URL is authorized and issued (ADR 0015 D5).
 * 在「已授权并签发」时调用。WP3-4 由 {@link ObjectAccessAudit} 写入既有 {@code AuditPort}。
 * <p>
 * Audit is recorded at issue time, not at S3 GET or local redeem (those may not hit the app).
 * 审计记在签发时，而不是 S3 GET 或本地兑换时（后者可能不再进应用）。
 */
@FunctionalInterface
public interface ObjectDownloadIssueAuditHook {

    void afterAuthorizedIssue(String tenantId, String objectKey, String actorSubjectId, Instant expiresAt);

    /**
     * Optional: a refused issue (no URL). Default no-op for test stubs.
     * 可选：签发被拒绝（无 URL）。测试桩默认为空操作。
     */
    default void afterDeniedIssue(String tenantId, String objectKey, String actorSubjectId) {
        // default no-op — 默认空操作
    }
}

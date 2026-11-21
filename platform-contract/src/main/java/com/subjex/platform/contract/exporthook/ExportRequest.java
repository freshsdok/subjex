package com.subjex.platform.contract.exporthook;

import java.time.Instant;

/**
 * ExportRequest — one personal-data export request in a business tenant, bound to a thin-workflow instance.
 * 业务租户内一条个人数据导出申请，绑定薄工作流实例。
 *
 * @param expiresAt when a READY artifact may no longer be downloaded (WP8-3) / READY 产物停止可下载的时间
 * @param artifactObjectKey WP3 object key of the generated bundle, if any / 已生成包的 WP3 对象键
 */
public record ExportRequest(
        String requestId,
        String tenantId,
        String subjectId,
        ExportRequesterRealm requesterRealm,
        String requesterId,
        String scopeDescriptor,
        ExportRequestStatus status,
        String approverSubjectId,
        String workflowInstanceId,
        Instant createdAt,
        Instant updatedAt,
        Instant decidedAt,
        Instant expiresAt,
        String artifactObjectKey) {

    /**
     * Request without an artifact yet (WP8-2 shape) — 尚无产物的申请（WP8-2 形状）。
     */
    public ExportRequest(
            String requestId,
            String tenantId,
            String subjectId,
            ExportRequesterRealm requesterRealm,
            String requesterId,
            String scopeDescriptor,
            ExportRequestStatus status,
            String approverSubjectId,
            String workflowInstanceId,
            Instant createdAt,
            Instant updatedAt,
            Instant decidedAt) {
        this(
                requestId,
                tenantId,
                subjectId,
                requesterRealm,
                requesterId,
                scopeDescriptor,
                status,
                approverSubjectId,
                workflowInstanceId,
                createdAt,
                updatedAt,
                decidedAt,
                null,
                null);
    }
}

package com.subjex.platform.app.exporthook;

import com.subjex.platform.app.workflow.WorkflowExportHook;
import com.subjex.platform.contract.exporthook.ExportRequest;
import com.subjex.platform.contract.exporthook.ExportRequestStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * ExportExpiryService — mark a READY request EXPIRED when {@code expiresAt} has passed (read or sweep).
 * READY 申请在 {@code expiresAt} 已过时标为 EXPIRED（读取或清扫）。
 */
public final class ExportExpiryService {

    private final JdbcExportRequestStore store;
    private final Clock clock;
    private final WorkflowExportHook exportHook;

    public ExportExpiryService(JdbcExportRequestStore store, Clock clock, WorkflowExportHook exportHook) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.exportHook = Objects.requireNonNull(exportHook, "exportHook");
    }

    /**
     * If READY and past expiry, persist EXPIRED and move the workflow; otherwise return the row unchanged.
     * 若已 READY 且过期，则落库 EXPIRED 并转移工作流；否则原样返回。
     */
    public ExportRequest expireIfNeeded(ExportRequest request) {
        Objects.requireNonNull(request, "request");
        Instant now = clock.instant();
        if (request.status() != ExportRequestStatus.READY
                || request.expiresAt() == null
                || request.expiresAt().isAfter(now)) {
            return request;
        }
        return markExpired(request, now);
    }

    /** Sweep every READY row whose expiry has passed — 清扫所有已过期的 READY 行. */
    public int sweep() {
        Instant now = clock.instant();
        int count = 0;
        for (ExportRequest request : store.listReadyExpired(now)) {
            markExpired(request, now);
            count++;
        }
        return count;
    }

    private ExportRequest markExpired(ExportRequest request, Instant now) {
        if (request.workflowInstanceId() != null && !request.workflowInstanceId().isBlank()) {
            exportHook.expire(request.workflowInstanceId());
        }
        ExportRequest expired = new ExportRequest(
                request.requestId(),
                request.tenantId(),
                request.subjectId(),
                request.requesterRealm(),
                request.requesterId(),
                request.scopeDescriptor(),
                ExportRequestStatus.EXPIRED,
                request.approverSubjectId(),
                request.workflowInstanceId(),
                request.createdAt(),
                now,
                request.decidedAt(),
                request.expiresAt(),
                request.artifactObjectKey());
        store.updateArtifact(expired);
        return expired;
    }
}

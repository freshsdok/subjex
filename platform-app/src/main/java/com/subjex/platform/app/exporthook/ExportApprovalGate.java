package com.subjex.platform.app.exporthook;

import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.contract.exporthook.ExportNotApprovedException;
import com.subjex.platform.contract.exporthook.ExportRequest;
import com.subjex.platform.contract.exporthook.ExportRequestStatus;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * ExportApprovalGate — fail-closed hook WP8-3 will call before generating an artifact.
 * WP8-3 生成产物前调用的失败关闭门禁：只有 {@code APPROVED} 才放行；拒绝会写入审计。
 */
public final class ExportApprovalGate {

    /** Audit action when the gate refuses — 门禁拒绝时的审计动作. */
    public static final String AUDIT_DENY = "export.gate.deny";

    private final JdbcExportRequestStore store;
    private final AuditPort audit;
    private final Clock clock;

    public ExportApprovalGate(JdbcExportRequestStore store, AuditPort audit, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Return the request only when its status is {@link ExportRequestStatus#APPROVED}.
     * 仅当状态为已批准时返回该申请；否则失败关闭并审计。
     */
    public ExportRequest requireApproved(String requestId) {
        return requireApproved(requestId, "system");
    }

    public ExportRequest requireApproved(String requestId, String actorIdentityId) {
        ExportRequest request = store.find(requestId).orElse(null);
        if (request == null || request.status() != ExportRequestStatus.APPROVED) {
            String tenant = request == null ? "_" : request.tenantId();
            String actor = actorIdentityId == null || actorIdentityId.isBlank() ? "system" : actorIdentityId.trim();
            String target = requestId == null || requestId.isBlank() ? "_" : requestId.trim();
            audit.record(new AuditEntry(
                    UUID.randomUUID().toString(),
                    tenant,
                    actor,
                    AUDIT_DENY,
                    target,
                    AuditOutcome.REFUSED,
                    clock.instant()));
            throw new ExportNotApprovedException();
        }
        return request;
    }
}

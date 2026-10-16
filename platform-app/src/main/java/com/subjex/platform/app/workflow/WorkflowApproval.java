package com.subjex.platform.app.workflow;

import java.time.Instant;
import java.util.Objects;

/**
 * WorkflowApproval — PENDING→CONSUMED dual-approve row (WF-2 / ADR 0005).
 * 双人审批行：PENDING→CONSUMED（WF-2 / ADR 0005）。
 */
public record WorkflowApproval(
        String approvalId,
        String instanceId,
        String tenantId,
        String requestedBySubjectId,
        String approvalState,
        Instant createdAt,
        Instant consumedAt,
        String consumedBySubjectId,
        String decisionAction) {

    public static final String PENDING = "PENDING";
    public static final String CONSUMED = "CONSUMED";
    public static final String CANCELLED = "CANCELLED";

    public WorkflowApproval {
        Objects.requireNonNull(approvalId, "approvalId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(requestedBySubjectId, "requestedBySubjectId");
        Objects.requireNonNull(approvalState, "approvalState");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}

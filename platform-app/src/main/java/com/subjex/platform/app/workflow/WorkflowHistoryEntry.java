package com.subjex.platform.app.workflow;

import java.time.Instant;
import java.util.Objects;

/**
 * WorkflowHistoryEntry — one applied transition (audit trail).
 * 一次已应用流转（审计轨迹）。
 */
public record WorkflowHistoryEntry(
        String historyId,
        String instanceId,
        String tenantId,
        WorkflowState fromState,
        String actionName,
        WorkflowState toState,
        String actorSubjectId,
        String noteText,
        String capabilityId,
        Instant createdAt) {

    public WorkflowHistoryEntry {
        Objects.requireNonNull(historyId, "historyId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actionName, "actionName");
        Objects.requireNonNull(toState, "toState");
        Objects.requireNonNull(actorSubjectId, "actorSubjectId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
